<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // 1. Connected Android Devices (Multi-SIM & Live WebSocket Status)
        Schema::create('gateway_devices', function (Blueprint $table) {
            $table->id();
            $table->string('device_name');
            $table->string('device_token', 64)->unique();
            $table->boolean('is_online')->default(false);
            $table->unsignedTinyInteger('battery_level')->default(100);
            $table->unsignedSmallInteger('ping_ms')->default(15);
            $table->timestamp('last_seen_at')->nullable();
            $table->timestamps();
        });

        // 2. Multi-SIM Merchant Numbers (Load Balancing Pool)
        Schema::create('merchant_numbers', function (Blueprint $table) {
            $table->id();
            $table->enum('provider', ['bkash', 'nagad', 'rocket', 'upay'])->index();
            $table->enum('account_type', ['personal', 'agent', 'merchant'])->default('personal');
            $table->string('phone_number', 20);
            $table->foreignId('device_id')->nullable()->constrained('gateway_devices')->nullOnDelete();
            $table->boolean('is_active')->default(true)->index();
            $table->unsignedInteger('active_load')->default(0);
            $table->unsignedBigInteger('total_processed')->default(0);
            $table->timestamps();
        });

        // 3. Incoming SMS / Push Notification / Statement Transactions
        // CRITICAL SECURITY: trx_id is strictly UNIQUE at DB level to prevent Double Spend
        Schema::create('incoming_transactions', function (Blueprint $table) {
            $table->id();
            $table->enum('provider', ['bkash', 'nagad', 'rocket', 'upay', 'card'])->index();
            $table->string('trx_id', 64)->unique(); // Layer 1 Lock: Unique Index
            $table->decimal('amount', 12, 2)->index();
            $table->string('sender_number', 25)->nullable();
            $table->string('receiver_number', 25)->nullable();
            $table->enum('source_type', ['push_notification', 'sms_reader', 'statement_scanner', 'manual_admin', 'card_gateway'])
                  ->default('push_notification');
            $table->enum('status', ['unused', 'used', 'flagged'])->default('unused')->index(); // Layer 2 Lock: Status Binding
            $table->string('used_by_invoice_id', 64)->nullable()->index();
            $table->text('raw_message')->nullable();
            $table->timestamp('verified_at')->nullable();
            $table->timestamps();

            // Composite index for sub-millisecond matching under 200+ concurrent requests
            $table->index(['trx_id', 'provider', 'status', 'amount'], 'idx_fast_trx_match');
        });

        // 4. Customer Payment Invoices
        Schema::create('payment_invoices', function (Blueprint $table) {
            $table->id();
            $table->string('invoice_id', 64)->unique();
            $table->string('merchant_order_id', 64)->index();
            $table->string('customer_name')->nullable();
            $table->string('customer_email')->nullable();
            $table->string('customer_phone', 25)->nullable();
            $table->decimal('amount', 12, 2);
            $table->enum('selected_method', ['bkash', 'nagad', 'rocket', 'upay', 'card'])->nullable();
            $table->string('assigned_sim_number', 20)->nullable();
            $table->string('submitted_trx_id', 64)->nullable()->index();
            $table->enum('status', ['pending', 'processing_scan', 'paid', 'expired', 'failed'])->default('pending')->index();
            $table->string('verified_via', 40)->nullable();
            $table->string('webhook_url')->nullable();
            $table->string('redirect_url')->nullable();
            $table->timestamp('paid_at')->nullable();
            $table->timestamp('expires_at')->nullable();
            $table->timestamps();
        });

        // 5. Gateway Global Settings (Optional Card Switch, API Keys, Webhook Secret)
        Schema::create('gateway_settings', function (Blueprint $table) {
            $table->id();
            $table->string('key')->unique();
            $table->text('value')->nullable();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('gateway_settings');
        Schema::dropIfExists('payment_invoices');
        Schema::dropIfExists('incoming_transactions');
        Schema::dropIfExists('merchant_numbers');
        Schema::dropIfExists('gateway_devices');
    }
};
