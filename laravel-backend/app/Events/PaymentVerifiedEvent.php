<?php

namespace App\Events;

use Illuminate\Broadcasting\Channel;
use Illuminate\Broadcasting\InteractsWithSockets;
use Illuminate\Contracts\Broadcasting\ShouldBroadcastNow;
use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PaymentVerifiedEvent implements ShouldBroadcastNow
{
    use Dispatchable, InteractsWithSockets, SerializesModels;

    public function __construct(
        public string $invoiceId,
        public string $trxId,
        public string $provider
    ) {}

    public function broadcastOn(): array
    {
        return [
            new Channel('invoice.' . $this->invoiceId),
            new Channel('admin.monitor'),
        ];
    }
}
