# Purchase & Sales Software — Android V1

Native Android purchasing, inventory, sales, VAT and invoicing app built for a fast phone-first workflow.

## Core workflow

`Purchase → Pending Receipt → Inventory → Sale → Customer Invoice`

Purchases remain editable and track received, cancelled and returned quantities independently. Supplier refunds can be pending or received, and received stock can be returned later without deleting the original purchase history.

## Included in V1

- Native Android app using Kotlin + Jetpack Compose.
- Fast local Room/SQLite database; normal use does not depend on internet connectivity.
- Purchase entry: date, free-text supplier/store, item, quantity, order number, email/username, gross cost, automatic net/VAT, payment method, notes and optional invoice attachment.
- VAT treatments limited to the required three: Standard VAT 20%, Reverse VAT and No VAT.
- Reverse VAT purchases store £0 actual VAT plus a 20% notional input/output amount, which nets to £0 in the VAT position.
- Pending Receipt tab with Receipt Pending, Partially Received, Received, Cancelled, Returned, Refund Pending and Refund Received states derived from the transaction quantities/refund values.
- Purchase invoices can be attached when the purchase is first entered or added/replaced later.
- Inventory is created only from physically received quantities and reduced by supplier returns and sales.
- Sales support multiple items on one invoice and stock is automatically allocated to received purchase lots for cost/profit calculations.
- Saved customer cards with a configurable 2–5 character invoice code.
- Invoice numbering: `CUS-DDMMYY-##`, counted independently for each customer on each day.
- PDF sales invoices containing business/customer/VAT information and reverse-charge wording where applicable.
- Customer return/refund records, with an option to return the item to inventory or leave it non-resalable.
- Expenses with VAT, payment method and receipt/invoice attachment.
- Business Details: company information, VAT number, company number, bank details, invoice terms/footer and accounting period.
- Profit & VAT analysis: net sales, COGS, gross profit, expenses, net profit, output VAT, recoverable input VAT, reverse VAT notional entries, VAT due to HMRC or VAT refund expected, inventory cost and supplier refunds pending.
- Excel export matching the supplied workbook structure with `PUR`, `SALES`, `EXPENSES`, plus `PROFIT & VAT`.
- Supplier/customer returns are represented as negative financial rows in the exported workbook rather than silently overwriting the original transaction.
- Android folder browser for bulk export of all purchase invoices, sales PDFs and expense receipts.
- Dropbox background sync for invoice/receipt copies, the current accounting-period Excel workbook and human-readable recovery dumps:
  - `PURCHASES.txt`
  - `PENDING_PURCHASES.txt`
  - `INVENTORY.txt`

## Dropbox setup

V1 deliberately avoids a separate backup/security subsystem. The database remains local and Dropbox is used as the external document/recovery copy.

Open **More → Business Details → Dropbox**. You can use either:

1. a Dropbox access token, or
2. a Dropbox App Key + refresh token for persistent background sync.

Enable **Automatic Dropbox sync**. Sync runs in WorkManager on a network connection and never blocks local purchase/sale entry.

Default Dropbox root: `/Purchase-Sales-Software`.

## Accounting rules used

### Standard VAT
If gross is £1,200, the app records net £1,000 and VAT £200.

### Reverse VAT purchase
If the supplier charge is £1,000, the app records net/gross £1,000, VAT actually paid £0, and notional reverse VAT £200 on both input and output sides. Net VAT effect is £0 where fully recoverable.

### Reverse VAT sale
The customer invoice shows £0 VAT and reverse-charge wording. The app does not treat notional VAT as normal VAT collected from the customer.

### Supplier refund
A purchase refund/credit reduces the purchase-side VAT position using the VAT treatment of the original purchase. The original purchase remains intact for traceability.

## APK workflow artifact

Every push to `feature/android-v1-complete` or `main` runs `.github/workflows/android-apk.yml`.

The workflow builds an installable debug APK and uploads it as the GitHub Actions artifact:

**Purchase-Sales-Software-APK**

The artifact contains `app-debug.apk`, which can be downloaded to and installed on a Google Pixel 10 Pro XL or another supported Android phone (Android 9/API 28 or newer).

## Development

- Kotlin 2.0.21
- Jetpack Compose / Material 3
- Room
- WorkManager
- minSdk 28
- target/compile SDK 35
- Java 17

The app intentionally has no FEFO/expiry logic, no supplier master-data module, no roles/users and no conventional backup/restore UI in V1.

Build branch: `feature/android-v1-complete`.
