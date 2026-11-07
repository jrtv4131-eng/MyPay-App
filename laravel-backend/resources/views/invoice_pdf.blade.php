<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>Payment Receipt - {{ $invoice->invoice_id }}</title>
    <style>
        body { font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; color: #1e293b; padding: 30px; }
        .box { border: 2px solid #e2e8f0; border-radius: 12px; padding: 28px; position: relative; }
        .paid-stamp { position: absolute; top: 28px; right: 28px; border: 3px solid #16a34a; color: #16a34a; font-weight: bold; font-size: 20px; padding: 6px 16px; border-radius: 8px; text-transform: uppercase; }
        table { width: 100%; border-collapse: collapse; margin-top: 20px; }
        th, td { padding: 12px; border-bottom: 1px solid #e2e8f0; text-align: left; }
        .total { font-size: 20px; font-weight: bold; color: #0f172a; }
    </style>
</head>
<body>
    <div class="box">
        <div class="paid-stamp">PAID & VERIFIED</div>
        <h2>MyPay BD - Official Payment Receipt</h2>
        <p>Invoice ID: <strong>{{ $invoice->invoice_id }}</strong> | Order ID: <strong>{{ $invoice->merchant_order_id }}</strong></p>
        <table>
            <tr><th>Payment Method</th><td>{{ strtoupper($invoice->selected_method) }}</td></tr>
            <tr><th>Transaction ID (TrxID)</th><td><strong>{{ $invoice->submitted_trx_id }}</strong></td></tr>
            <tr><th>Verification Engine</th><td>{{ strtoupper($invoice->verified_via) }}</td></tr>
            <tr><th>Paid Date & Time</th><td>{{ $invoice->paid_at }}</td></tr>
            <tr><th>Total Amount Paid</th><td class="total">BDT {{ number_format($invoice->amount, 2) }}</td></tr>
        </table>
    </div>
</body>
</html>
