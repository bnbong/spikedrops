# SpikeDrops Pay Transaction Scenarios

## 1. 공통 원칙

1. 장바구니 담기는 재고를 예약하지 않는다.
2. 재고 예약은 주문 생성 또는 장바구니 checkout transaction에서 수행한다.
3. 외부 PG 호출은 DB transaction 밖에서 수행한다.
4. 결제 승인과 주문 만료는 같은 주문에 대해 동시에 성공할 수 없다.
5. 중복 checkout, 중복 결제 승인, 중복 환불 요청은 멱등하게 처리한다.
6. 다중 재고를 변경하는 transaction은 고정된 순서로 재고를 점유한다.

## 2. Scenario 1 — Add Item to Cart - v1

| 항목           | 내용                             |
|--------------|--------------------------------|
| 목적           | 사용자가 `DropItem`을 장바구니에 담는다.    |
| 참여 Aggregate | `Customer`, `Cart`, `DropItem` |
| 재고 변경        | 없음                             |

### Preconditions

```text
Customer.status == ACTIVE
Cart.status == ACTIVE or Cart not exists
DropItem.status in (READY, ON_SALE)
quantity > 0
quantity <= DropItem.purchaseLimitPerUser
```

### Transaction Boundary

```text
Tx
1. ACTIVE Cart 조회 또는 생성
2. CartItem 추가 또는 기존 CartItem 수량 증가
3. CartItem 수량 제한 검증
Commit
```

### Postconditions

```text
Cart.status = ACTIVE
CartItem.status = ACTIVE
Inventory unchanged
```

## 3. Scenario 2 — Update CartItem Quantity - v1

| 항목 | 내용 |
|---|---|
| 목적 | 장바구니 항목 수량을 변경한다. |
| 참여 Aggregate | `Cart`, `DropItem` |
| 재고 변경 | 없음 |

### Preconditions

```text
Cart.status == ACTIVE
CartItem.status == ACTIVE
quantity > 0
quantity <= DropItem.purchaseLimitPerUser
```

### Transaction Boundary

```text
Tx
1. ACTIVE Cart 조회
2. CartItem 소유 여부 검증
3. 수량 변경
Commit
```

### Postconditions

```text
CartItem.quantity updated
Inventory unchanged
```

## 4. Scenario 3 — Remove CartItem - v1

| 항목 | 내용 |
|---|---|
| 목적 | 장바구니 항목을 제거한다. |
| 참여 Aggregate | `Cart` |
| 재고 변경 | 없음 |

### Transaction Boundary

```text
Tx
1. ACTIVE Cart 조회
2. CartItem 소유 여부 검증
3. CartItem.status = REMOVED
Commit
```

### Postconditions

```text
CartItem.status = REMOVED
Inventory unchanged
```

## 5. Scenario 4 — Checkout Cart - v1

| 항목           | 내용                                                                           |
|--------------|------------------------------------------------------------------------------|
| 목적           | 장바구니 항목을 주문으로 변환하고 재고를 예약한다.                                                 |
| 참여 Aggregate | `Customer`, `Cart`, `DropEvent`, `DropItem`, `Inventory`, `Order`, `Payment` |
| 재고 변경        | `available -> reserved`                                                      |

### Preconditions

```text
Customer.status == ACTIVE
Cart.status == ACTIVE
Cart has at least one ACTIVE CartItem
DropEvent.status == OPEN
DropItem.status == ON_SALE
Inventory.availableQuantity >= requestedQuantity
```

### Transaction Boundary

```text
Tx
1. checkout idempotency 확인
2. Cart 상태를 CHECKOUT_PROCESSING으로 전이
3. CartItem 목록 기준 판매 가능 여부 검증
4. Inventory 예약
5. Order, OrderLine 생성
6. Payment READY 생성
7. CartItem.status = CONVERTED_TO_ORDER
8. Cart.status = CHECKED_OUT
Commit
```

### Postconditions

```text
Cart.status = CHECKED_OUT
Order.status = PAYMENT_READY
Payment.status = READY
Inventory.availableQuantity decreased
Inventory.reservedQuantity increased
```

## 6. Scenario 5 — Direct Order Creation - MVP

| 항목 | 내용 |
|---|---|
| 목적 | 장바구니 없이 즉시 주문을 생성하고 재고를 예약한다. |
| 참여 Aggregate | `Customer`, `DropEvent`, `DropItem`, `Inventory`, `Order`, `Payment` |
| 재고 변경 | `available -> reserved` |

### Preconditions

```text
Customer.status == ACTIVE
DropEvent.status == OPEN
DropItem.status == ON_SALE
quantity > 0
quantity <= DropItem.purchaseLimitPerUser
Inventory.availableQuantity >= quantity
```

### Transaction Boundary

```text
Tx
1. 판매 가능 여부 검증
2. Inventory 예약
3. Order, OrderLine 생성
4. Payment READY 생성
Commit
```

### Postconditions

```text
Order.status = PAYMENT_READY
Payment.status = READY
Inventory.availableQuantity decreased
Inventory.reservedQuantity increased
```

## 7. Scenario 6 — Payment Confirm

| 항목 | 내용 |
|---|---|
| 목적 | 결제 승인 요청을 처리한다. |
| 참여 Aggregate | `Payment`, `Order`, `Inventory`, `Ledger`, `OutboxEvent` |
| 외부 호출 | PG confirm |

### Tx1 — Confirm Start

```text
Tx1
1. Payment 멱등성 확인
2. Payment.status = PROCESSING
3. Order.status = PAYMENT_PROCESSING
Commit
```

### External Call

```text
PG confirm 호출
```

### Tx2 — Approved

```text
Tx2
1. Payment.status = APPROVED
2. Order.status = PAID
3. Inventory reserved -> sold
4. LedgerTransaction 생성
5. OutboxEvent 생성
Commit
```

### Tx2 — Failed

```text
Tx2
1. Payment.status = FAILED
2. Order.status = PAYMENT_FAILED
3. Inventory reserved -> available
4. OutboxEvent 생성
Commit
```

### Tx2 — Unknown Result

```text
Tx2
1. Payment.status = CONFIRM_RETRY_PENDING
2. Order.status = PAYMENT_PROCESSING 유지
3. RetryTask 생성
Commit
```

## 8. Scenario 7 — Duplicate Payment Confirm

| 항목           | 내용                        |
|--------------|---------------------------|
| 목적           | 같은 결제 승인 요청의 중복 처리를 방지한다. |
| 참여 Aggregate | `Payment`, `Order`        |

### Transaction Boundary

```text
Tx
1. idempotencyKey 또는 Payment 식별자로 기존 처리 상태 확인
2. READY이면 PROCESSING 전이 허용
3. PROCESSING이면 처리 중 응답
4. APPROVED 또는 FAILED이면 기존 결과 반환
Commit
```

### Postconditions

```text
PG confirm is not duplicated for the same payment intent
Payment result is reflected once
```

## 9. Scenario 8 — Order Expiration

| 항목           | 내용                                 |
|--------------|------------------------------------|
| 목적           | 결제 제한 시간이 지난 주문을 만료하고 예약 재고를 복구한다. |
| 참여 Aggregate | `Order`, `Payment`, `Inventory`    |
| 재고 변경        | `reserved -> available`            |

### Preconditions

```text
Order.status == PAYMENT_READY
Order.expiresAt < now
```

### Transaction Boundary

```text
Tx
1. 만료 대상 Order 조회
2. Order.status = EXPIRED
3. Payment.status = FAILED or CANCELED
4. Inventory reserved -> available
Commit
```

### Postconditions

```text
Order.status = EXPIRED
Inventory.reservedQuantity decreased
Inventory.availableQuantity increased
```

## 10. Scenario 9 — Cancel Order Before Payment

| 항목           | 내용                              |
|--------------|---------------------------------|
| 목적           | 결제 전 주문을 취소하고 예약 재고를 복구한다.      |
| 참여 Aggregate | `Order`, `Payment`, `Inventory` |
| 재고 변경        | `reserved -> available`         |

### Preconditions

```text
Order.status == PAYMENT_READY
Payment.status == READY
```

### Transaction Boundary

```text
Tx
1. Order.status = CANCELED
2. Payment.status = CANCELED
3. Inventory reserved -> available
Commit
```

### Postconditions

```text
Order.status = CANCELED
Payment.status = CANCELED
Inventory reservation released
```

## 11. Scenario 10 — Payment Confirm vs Order Expiration

| 항목           | 내용                                 |
|--------------|------------------------------------|
| 목적           | 결제 승인과 주문 만료가 동시에 발생할 때 정합성을 보장한다. |
| 참여 Aggregate | `Payment`, `Order`, `Inventory`    |

### Valid Outcomes

```text
Case A: Payment confirm wins
Order: PAYMENT_READY -> PAYMENT_PROCESSING -> PAID
Payment: READY -> PROCESSING -> APPROVED
Inventory: reserved -> sold

Case B: Expiration wins
Order: PAYMENT_READY -> EXPIRED
Payment: READY -> FAILED or CANCELED
Inventory: reserved -> available
```

### Invalid Outcomes

```text
Order.status = PAID and Inventory reservation released
Order.status = EXPIRED and Payment.status = APPROVED
Inventory.reservedQuantity < 0
```

## 12. Scenario 11 — Refund Request - v1

| 항목           | 내용                                                    |
|--------------|-------------------------------------------------------|
| 목적           | 결제 완료 주문을 환불한다.                                       |
| 참여 Aggregate | `Refund`, `Payment`, `Order`, `Ledger`, `OutboxEvent` |
| 외부 호출        | PG cancel                                             |

### Tx1 — Refund Start

```text
Tx1
1. Refund 멱등성 확인
2. Payment.status == APPROVED 검증
3. Order.status == PAID 검증
4. Refund.status = PROCESSING
5. Order.status = REFUND_REQUESTED
Commit
```

### External Call

```text
PG cancel 호출
```

### Tx2 — Completed

```text
Tx2
1. Refund.status = COMPLETED
2. Payment.status = CANCELED
3. Order.status = REFUNDED
4. LedgerTransaction 생성
5. OutboxEvent 생성
Commit
```

### Tx2 — Failed or Unknown

```text
Tx2
1. Refund.status = FAILED or RETRY_PENDING
2. Order.status = PAID or REFUND_REQUESTED
3. RetryTask 생성
Commit
```

## 13. Scenario 12 — Outbox Publish - v1

| 항목    | 내용                     |
|-------|------------------------|
| 목적    | 저장된 도메인 이벤트를 외부로 발행한다. |
| 참여 모델 | `OutboxEvent`          |

### Transaction Boundary

```text
Tx1
1. 발행 대상 OutboxEvent 점유
2. status = PUBLISHING
Commit

External publish

Tx2
1. 성공 시 status = PUBLISHED
2. 실패 시 status = RETRY_PENDING or DEAD_LETTER
Commit
```

## 14. Scenario 13 — RetryTask Execution - v1

| 항목    | 내용                         |
|-------|----------------------------|
| 목적    | 실패한 외부 호출 또는 후속 작업을 재시도한다. |
| 참여 모델 | `RetryTask`                |

### Transaction Boundary

```text
Tx1
1. 실행 대상 RetryTask 점유
2. status = RUNNING
Commit

Task execution

Tx2
1. 성공 시 status = SUCCEEDED
2. 재시도 가능 실패 시 status = WAITING_RETRY
3. 실패 확정 시 status = FAILED or DEAD_LETTER
Commit
```

## 15. Scenario 14 — Cart Cleanup - v1

| 항목           | 내용                   |
|--------------|----------------------|
| 목적           | 장기 미사용 장바구니를 비활성화한다. |
| 참여 Aggregate | `Cart`               |
| 재고 변경        | 없음                   |

### Preconditions

```text
Cart.status == ACTIVE
Cart.updatedAt < cleanup threshold
```

### Transaction Boundary

```text
Tx
1. 대상 Cart 조회
2. Cart.status = ABANDONED or EXPIRED
Commit
```

### Postconditions

```text
Cart is not checkoutable
Inventory unchanged
```