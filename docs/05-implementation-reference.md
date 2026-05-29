# SpikeDrops Pay Implementation Reference

## 1. 문서 목적

이 문서는 구현 단계에서 확인할 작업 항목, 설계 판단 메모, 테스트 기준, 계측 포인트를 정리한다.

## 2. 문서 분리 기준

| 문서                               | 포함할 내용                         | 제외할 내용                        |
|----------------------------------|--------------------------------|-------------------------------|
| `01-domain-rules.md`             | 비즈니스 규칙, 불변식                   | 구현 방식, API, DB, 테스트           |
| `02-domain-model.md`             | Aggregate 경계, Entity/VO, 참조 규칙 | 상태 전이, transaction 흐름         |
| `03-state-machine.md`            | 상태, 허용 전이, 금지 전이               | transaction boundary, lock 방식 |
| `04-transaction-scenarios.md`    | Tx 경계, 참여 Aggregate, 결과 상태     | 코드 구현 팁, 테스트 상세, 모니터링         |
| `05-implementation-reference.md` | 구현 순서, 체크리스트, 테스트, 계측          | 도메인 원문 정의                     |

## 3. 구현 우선순위

### Phase 1 — MVP Direct Order

1. `Customer`, `Product`, `DropEvent`, `DropItem`, `Inventory`, `Order`, `Payment` Entity 작성
2. Flyway V1 schema 작성
3. seed data 작성
4. direct order 생성 구현
5. Inventory 예약 단위 테스트 작성
6. 동시 주문 통합 테스트 작성

### Phase 2 — Payment

1. `PaymentGateway` interface 작성
2. `MockPaymentGateway` 구현
3. `PaymentService.confirm()` 구현
4. PG 호출 transaction 분리
5. 중복 confirm 테스트 작성
6. 주문 만료와 결제 승인 race condition 테스트 작성

### Phase 3 — Cart v1

1. `Cart`, `CartItem` 추가
2. 장바구니 담기/수정/삭제 구현
3. cart checkout 구현
4. 중복 checkout 방지 테스트 작성
5. cart cleanup scheduler 구현

### Phase 4 — Refund / Outbox / Retry

1. `Refund` 추가
2. PG cancel 흐름 구현
3. `LedgerTransaction`, `LedgerEntry` 추가
4. `OutboxEvent` 추가
5. `RetryTask` 추가
6. worker 병렬 처리 테스트 작성

## 4. DB Modeling Checklist

### MVP 테이블

```text
customers
products
drop_events
drop_items
inventory
orders
order_lines
payments
```

### v1 테이블

```text
carts
cart_items
refunds
ledger_transactions
ledger_entries
outbox_events
retry_tasks
```

### 핵심 제약 조건 후보

```text
inventory.drop_item_id unique
orders.order_no unique
payments.order_id unique
payments.payment_key unique
payments.idempotency_key unique
refunds.idempotency_key unique
cart_items(cart_id, drop_item_id) unique where status = 'ACTIVE'
```

### 핵심 check constraint 후보

```text
inventory.total_quantity >= 0
inventory.available_quantity >= 0
inventory.reserved_quantity >= 0
inventory.sold_quantity >= 0
order_lines.quantity > 0
cart_items.quantity > 0
payments.amount >= 0
refunds.amount >= 0
```

## 5. Lock / Concurrency Reference

| 유스케이스               | 권장 전략                          | 비고                  |
|---------------------|--------------------------------|---------------------|
| direct order        | `Inventory` pessimistic lock   | MVP 우선 구현           |
| cart checkout       | `Cart` lock + `Inventory` lock | 중복 checkout 방지      |
| multi-item checkout | dropItemId 오름차순 lock           | deadlock 방지         |
| payment confirm     | `Payment` lock 또는 상태 조건 update | PG 중복 호출 방지         |
| order expiration    | 대상 order batch 점유              | `PAYMENT_READY`만 처리 |
| outbox worker       | `SKIP LOCKED` 후보               | v1 이후               |
| retry worker        | `SKIP LOCKED` 후보               | v1 이후               |

## 6. Repository Method 후보

```text
InventoryRepository.findByDropItemIdForUpdate(dropItemId)
InventoryRepository.findAllByDropItemIdsForUpdateOrderByDropItemId(dropItemIds)
PaymentRepository.findByOrderIdForUpdate(orderId)
OrderRepository.findExpiredPaymentReadyOrders(now, limit)
CartRepository.findActiveByCustomerIdForUpdate(customerId)
OutboxRepository.findPublishableForUpdateSkipLocked(now, limit)
RetryTaskRepository.findExecutableForUpdateSkipLocked(now, limit)
```

## 7. API 후보

### MVP

```text
GET  /api/drop-events/current
GET  /api/drop-items/{dropItemId}
POST /api/orders
GET  /api/orders/{orderId}
POST /api/payments/confirm
POST /api/orders/{orderId}/cancel
```

### v1

```text
GET    /api/cart
POST   /api/cart/items
PATCH  /api/cart/items/{cartItemId}
DELETE /api/cart/items/{cartItemId}
POST   /api/cart/checkout
POST   /api/refunds
```

## 8. Test Checklist

### Domain Unit Test

```text
Inventory.reserve()
Inventory.confirmSale()
Inventory.releaseReservation()
Order.markPaymentProcessing()
Order.markPaid()
Order.expire()
Payment.startProcessing()
Payment.approve()
Payment.fail()
Cart.addItem()
Cart.checkoutProcessing()
Refund.complete()
```

### Integration Test

```text
재고 10개 중 3개 주문 시 available 7, reserved 3
재고 2개인데 3개 주문 시 실패
재고 100개에 300개 동시 주문 시 성공 주문 수 <= 100
장바구니 담기 시 Inventory 변경 없음
cart checkout 성공 시 Inventory available -> reserved
동일 cart checkout 중복 요청 시 Order 1개만 생성
동일 payment confirm 중복 요청 시 PG confirm 1회만 호출
payment confirm과 order expiration 동시 실행 시 PAID/EXPIRED 충돌 없음
```

## 9. k6 Scenario 후보

```text
direct-order-contention.js
cart-checkout-contention.js
payment-confirm-idempotency.js
payment-confirm-vs-expiration.js
multi-item-deadlock.js
order-list-read-heavy.js
refund-retry.js
```

## 10. Observability Checklist

### Application Metric

```text
http.server.requests p95/p99
order.create.success.count
order.create.failed.soldout.count
payment.confirm.success.count
payment.confirm.duplicate.count
payment.confirm.retry.count
inventory.oversell.count
cart.checkout.duplicate.count
```

### DB / Connection Metric

```text
Hikari active connections
Hikari pending threads
slow query count
lock wait count
transaction duration
deadlock count
```

### Business Invariant Metric

```text
availableQuantity >= 0
reservedQuantity >= 0
soldQuantity >= 0
totalQuantity == available + reserved + sold
PAID order expired count == 0
duplicate payment approved count == 0
```

## 11. Git Tag 기준

```text
v0-schema-and-seed
v1-direct-order-naive
v2-inventory-pessimistic-lock
v3-payment-confirm-tx-split
v4-order-expiration-race-fixed
v5-cart-checkout
v6-outbox-retry
v7-monitoring-dashboard
```

## 12. 기록 포맷

```text
문제:
- 어떤 부하/동시성 상황에서 어떤 문제가 발생했는가

계측:
- 어떤 지표로 문제를 확인했는가

원인:
- transaction 경계, lock 순서, query, connection pool 중 무엇이 원인이었는가

개선:
- 어떤 설계 변경을 적용했는가

결과:
- p95 latency, lock wait, deadlock, 성공/실패 수, oversell 여부가 어떻게 바뀌었는가
```

## 13. 구현 시 주의할 점

1. 처음부터 Redis 대기열로 우회하지 않는다. DB 동시성 문제를 먼저 재현한다.
2. PG confirm을 DB transaction 안에서 호출하지 않는다.
3. `Order`, `Payment`, `Inventory` 상태를 한 번에 덮어쓰지 않는다.
4. 모든 상태 변경은 현재 상태 조건을 확인한 뒤 수행한다.
5. 실패 케이스를 성공 케이스만큼 먼저 작성한다.
6. 성능 수치는 실제 측정 전까지 확정값처럼 쓰지 않는다.