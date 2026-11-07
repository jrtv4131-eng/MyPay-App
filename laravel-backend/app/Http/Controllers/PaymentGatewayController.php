<?php

namespace App\Http\Controllers;

use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Http;
use Barryvdh\DomPDF\Facade\Pdf;
use App\Events\PaymentVerifiedEvent;
use App\Events\RequestStatementScanEvent;

class PaymentGatewayController extends Controller
{
    /**
     * 1. Show Checkout Page with Multi-SIM Load Balancing
     */
    public function showCheckout(string $invoiceId)
    {
        $invoice = DB::table('payment_invoices')->where('invoice_id', $invoiceId)->firstOrFail();

        if ($invoice->status === 'paid') {
            return redirect()->route('gateway.invoice', $invoice->invoice_id);
        }

        // Load Balance: Pick active SIM with lowest active_load for each provider
        $simPool = DB::table('merchant_numbers')
            ->where('is_active', true)
            ->orderBy('active_load', 'asc')
            ->get()
            ->groupBy('provider');

        $cardEnabled = DB::table('gateway_settings')->where('key', 'enable_card_payment')->value('value') === '1';

        return view('checkout', compact('invoice', 'simPool', 'cardEnabled'));
    }

    /**
     * 2. High-Concurrency Verification (100-200 Simultaneous Users)
     * Uses 3-Layer Anti-Double-Spend Lock:
     *   Layer 1: Redis Atomic Lock (Cache::lock) blocks concurrent race conditions
     *   Layer 2: MySQL InnoDB Row-Level Lock (lockForUpdate)
     *   Layer 3: UNIQUE trx_id + status ('unused' -> 'used') + invoice_id binding
     */
    public function verifyPayment(Request $request, string $invoiceId)
    {
        $request->validate([
            'provider' => 'required|in:bkash,nagad,rocket,upay,card',
            'trx_id'   => 'required|string|min:6|max:32|alpha_num',
        ]);

        $trxId = strtoupper(trim($request->trx_id));
        $provider = $request->provider;

        // LAYER 1: Redis Atomic Lock (10 seconds) - prevents simultaneous click race conditions
        $lock = Cache::lock('verify_trx_lock_' . $trxId, 10);

        if (! $lock->get()) {
            return response()->json([
                'status'  => 'error',
                'code'    => 'CONCURRENT_LOCK',
                'message' => 'This Transaction ID is currently being processed. Please wait a moment.'
            ], 429);
        }

        try {
            return DB::transaction(function () use ($invoiceId, $trxId, $provider) {
                $invoice = DB::table('payment_invoices')
                    ->where('invoice_id', $invoiceId)
                    ->lockForUpdate()
                    ->firstOrFail();

                if ($invoice->status === 'paid') {
                    return response()->json([
                        'status'       => 'paid',
                        'redirect_url' => route('gateway.invoice', $invoice->invoice_id)
                    ]);
                }

                // LAYER 2 & 3: Check if TrxID is already used by ANY invoice
                $existingTrx = DB::table('incoming_transactions')
                    ->where('trx_id', $trxId)
                    ->lockForUpdate()
                    ->first();

                if ($existingTrx && $existingTrx->status === 'used') {
                    return response()->json([
                        'status'  => 'error',
                        'code'    => 'DUPLICATE_TRX_ID',
                        'message' => 'Security Alert: This Transaction ID (' . $trxId . ') has already been used!'
                    ], 422);
                }

                // If found & unused, validate amount and mark as PAID immediately
                if ($existingTrx && $existingTrx->status === 'unused') {
                    if ((float) $existingTrx->amount < (float) $invoice->amount) {
                        return response()->json([
                            'status'  => 'error',
                            'code'    => 'AMOUNT_MISMATCH',
                            'message' => "Amount mismatch! Received Tk {$existingTrx->amount}, required Tk {$invoice->amount}."
                        ], 422);
                    }

                    // Bind TrxID permanently to this invoice
                    DB::table('incoming_transactions')
                        ->where('id', $existingTrx->id)
                        ->update([
                            'status'             => 'used',
                            'used_by_invoice_id' => $invoice->invoice_id,
                            'verified_at'        => now(),
                            'updated_at'         => now(),
                        ]);

                    DB::table('payment_invoices')
                        ->where('id', $invoice->id)
                        ->update([
                            'selected_method'  => $provider,
                            'submitted_trx_id' => $trxId,
                            'status'           => 'paid',
                            'verified_via'     => $existingTrx->source_type,
                            'paid_at'          => now(),
                            'updated_at'       => now(),
                        ]);

                    // Broadcast instant success via Laravel Reverb WebSocket
                    broadcast(new PaymentVerifiedEvent($invoice->invoice_id, $trxId, $provider));

                    // Dispatch asynchronous webhook to main e-commerce site
                    $this->dispatchMerchantWebhook($invoice->invoice_id);

                    return response()->json([
                        'status'       => 'paid',
                        'message'      => 'Payment verified in real-time!',
                        'invoice_url'  => route('gateway.invoice', $invoice->invoice_id),
                        'pdf_url'      => route('gateway.invoice.pdf', $invoice->invoice_id),
                    ]);
                }

                // BACKUP PLAN (SMS Late / Network Issue):
                // Save TrxID in 'processing_scan' state and trigger Android App Statement Auto-Scanner via WebSocket!
                DB::table('payment_invoices')
                    ->where('id', $invoice->id)
                    ->update([
                        'selected_method'  => $provider,
                        'submitted_trx_id' => $trxId,
                        'status'           => 'processing_scan',
                        'updated_at'       => now(),
                    ]);

                broadcast(new RequestStatementScanEvent($invoice->invoice_id, $provider, $trxId, (float) $invoice->amount));

                return response()->json([
                    'status'  => 'processing_scan',
                    'message' => 'SMS delayed on carrier network. Triggering Live App Statement Auto-Scanner on device...'
                ], 202);
            });
        } finally {
            $lock->release();
        }
    }

    /**
     * 3. Receive Real-Time Push Notification / SMS / Statement Scan from Android App
     */
    public function receiveDevicePayload(Request $request)
    {
        // Validate Secret Bearer Token from Android App
        $token = $request->bearerToken();
        $device = DB::table('gateway_devices')->where('device_token', $token)->first();

        if (! $device) {
            return response()->json(['error' => 'Unauthorized Device Token'], 401);
        }

        // Strict Official Sender ID Whitelist Validation
        $allowedSenders = ['BKASH', 'NAGAD', '16216', 'UPAY', 'APP_NOTIFICATION', 'STATEMENT_SCANNER'];
        $senderId = strtoupper($request->input('sender_id', ''));
        if (! in_array($senderId, $allowedSenders, true)) {
            return response()->json(['error' => 'Rejected: Unofficial Sender ID (' . $senderId . ')'], 403);
        }

        $trxId    = strtoupper(trim($request->input('trx_id')));
        $amount   = (float) $request->input('amount');
        $provider = strtolower($request->input('provider'));
        $source   = $request->input('source_type', 'push_notification');

        return DB::transaction(function () use ($trxId, $amount, $provider, $source, $request) {
            // Ignore if already stored (idempotent for dual SMS + Push Notification)
            $exists = DB::table('incoming_transactions')->where('trx_id', $trxId)->lockForUpdate()->first();
            if ($exists) {
                return response()->json(['status' => 'already_recorded', 'trx_id' => $trxId]);
            }

            // Check if a customer is already waiting in 'processing_scan' queue with this exact TrxID
            $waitingInvoice = DB::table('payment_invoices')
                ->where('submitted_trx_id', $trxId)
                ->whereIn('status', ['pending', 'processing_scan'])
                ->where('amount', '<=', $amount)
                ->lockForUpdate()
                ->first();

            $trxStatus = $waitingInvoice ? 'used' : 'unused';

            DB::table('incoming_transactions')->insert([
                'provider'           => $provider,
                'trx_id'             => $trxId,
                'amount'             => $amount,
                'sender_number'      => $request->input('customer_number'),
                'receiver_number'    => $request->input('sim_number'),
                'source_type'        => $source,
                'status'             => $trxStatus,
                'used_by_invoice_id' => $waitingInvoice ? $waitingInvoice->invoice_id : null,
                'raw_message'        => $request->input('raw_body'),
                'verified_at'        => $waitingInvoice ? now() : null,
                'created_at'         => now(),
                'updated_at'         => now(),
            ]);

            if ($waitingInvoice) {
                DB::table('payment_invoices')
                    ->where('id', $waitingInvoice->id)
                    ->update([
                        'status'       => 'paid',
                        'verified_via' => $source,
                        'paid_at'      => now(),
                        'updated_at'   => now(),
                    ]);

                // Instant WebSocket push to customer's browser -> shows Green Checkmark + Auto Invoice!
                broadcast(new PaymentVerifiedEvent($waitingInvoice->invoice_id, $trxId, $provider));
                $this->dispatchMerchantWebhook($waitingInvoice->invoice_id);
            }

            return response()->json([
                'status'       => 'recorded',
                'auto_matched' => (bool) $waitingInvoice,
                'invoice_id'   => $waitingInvoice?->invoice_id,
            ]);
        });
    }

    /**
     * 4. 1-Click Manual Approve from Admin Dashboard (Layer 4 Fallback)
     */
    public function manualApprove(Request $request, string $invoiceId)
    {
        return DB::transaction(function () use ($invoiceId) {
            $invoice = DB::table('payment_invoices')->where('invoice_id', $invoiceId)->lockForUpdate()->firstOrFail();
            $trxId = $invoice->submitted_trx_id ?: ('MANUAL-' . strtoupper(substr(md5($invoiceId . time()), 0, 8)));

            DB::table('incoming_transactions')->updateOrInsert(
                ['trx_id' => $trxId],
                [
                    'provider'           => $invoice->selected_method ?? 'bkash',
                    'amount'             => $invoice->amount,
                    'source_type'        => 'manual_admin',
                    'status'             => 'used',
                    'used_by_invoice_id' => $invoice->invoice_id,
                    'verified_at'        => now(),
                    'updated_at'         => now(),
                ]
            );

            DB::table('payment_invoices')->where('id', $invoice->id)->update([
                'submitted_trx_id' => $trxId,
                'status'           => 'paid',
                'verified_via'     => 'manual_admin',
                'paid_at'          => now(),
                'updated_at'       => now(),
            ]);

            broadcast(new PaymentVerifiedEvent($invoice->invoice_id, $trxId, $invoice->selected_method ?? 'bkash'));
            $this->dispatchMerchantWebhook($invoice->invoice_id);

            return response()->json(['status' => 'approved', 'invoice_id' => $invoiceId]);
        });
    }

    /**
     * 5. Download Official PDF Invoice (DomPDF)
     */
    public function downloadInvoicePdf(string $invoiceId)
    {
        $invoice = DB::table('payment_invoices')
            ->where('invoice_id', $invoiceId)
            ->where('status', 'paid')
            ->firstOrFail();

        $pdf = Pdf::loadView('invoice_pdf', compact('invoice'))->setPaper('a4', 'portrait');
        return $pdf->download("Invoice-{$invoice->invoice_id}.pdf");
    }

    protected function dispatchMerchantWebhook(string $invoiceId): void
    {
        $invoice = DB::table('payment_invoices')->where('invoice_id', $invoiceId)->first();
        if ($invoice && $invoice->webhook_url) {
            dispatch(function () use ($invoice) {
                Http::timeout(5)->post($invoice->webhook_url, [
                    'event'      => 'payment.completed',
                    'invoice_id' => $invoice->invoice_id,
                    'order_id'   => $invoice->merchant_order_id,
                    'amount'     => $invoice->amount,
                    'method'     => $invoice->selected_method,
                    'trx_id'     => $invoice->submitted_trx_id,
                    'paid_at'    => $invoice->paid_at,
                ]);
            });
        }
    }
}
