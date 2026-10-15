package com.apingu.purchasesales.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "business")
data class BusinessEntity(
    @PrimaryKey val id: Int = 1,
    val businessName: String = "",
    val address: String = "",
    val vatNumber: String = "",
    val companyNumber: String = "",
    val email: String = "",
    val phone: String = "",
    val bankDetails: String = "",
    val invoiceTerms: String = "",
    val invoiceFooter: String = "",
    val accountingStartEpochDay: Long = 0,
    val accountingEndEpochDay: Long = 0,
    val dropboxAccessToken: String = "",
    val dropboxAppKey: String = "",
    val dropboxRefreshToken: String = "",
    val dropboxRoot: String = "/Purchase-Sales-Software",
    val dropboxAutoSync: Boolean = false,
    @ColumnInfo(defaultValue = "0") val selectedAccountingPeriodId: Long = 0,
    @ColumnInfo(defaultValue = "''") val invoiceIdentifier: String = ""
)

@Entity(tableName = "accounting_periods")
data class AccountingPeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "customers", indices = [Index(value = ["invoiceCode"], unique = true)])
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyName: String,
    val invoiceCode: String,
    val address: String = "",
    val email: String = "",
    val phone: String = "",
    val vatNumber: String = "",
    val companyNumber: String = "",
    val notes: String = ""
)

@Entity(tableName = "purchase_orders", indices = [Index(value = ["purchaseDateEpochDay", "invoiceSupplierKey", "invoiceSequence"], unique = true)])
data class PurchaseOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseDateEpochDay: Long,
    val supplier: String,
    val orderNumber: String = "",
    val accountUsername: String = "",
    val vatType: String,
    val paymentMethod: String = "",
    val invoicePath: String? = null,
    val notes: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val invoiceSequence: Int = 0,
    @ColumnInfo(defaultValue = "''") val invoiceSupplierKey: String = ""
)


/**
 * Stable per-day, per-supplier sequence is kept on PurchaseOrderEntity.
 * Approved invoices are separate order-level documents; never duplicate documents per item line.
 */
@Entity(
    tableName = "purchase_invoices",
    foreignKeys = [ForeignKey(
        entity = PurchaseOrderEntity::class,
        parentColumns = ["id"],
        childColumns = ["purchaseOrderId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("purchaseOrderId"),
        Index(value = ["purchaseOrderId", "invoiceOrdinal"], unique = true)
    ]
)
data class PurchaseInvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseOrderId: Long,
    val invoiceOrdinal: Int,
    val filePath: String,
    val originalFileName: String = "",
    val source: String = "MANUAL",
    val sourceAccountEmail: String = "",
    val sourceMessageId: String? = null,
    val sourceAttachmentId: String? = null,
    val sha256: String = "",
    val byteSize: Long = 0,
    val approvedAtMillis: Long = System.currentTimeMillis(),
    val createdAtMillis: Long = System.currentTimeMillis()
)

/** Metadata only: OAuth credentials are not stored in this unencrypted Room table. */
@Entity(tableName = "email_accounts", indices = [Index(value = ["emailAddress"], unique = true)])
data class EmailAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val emailAddress: String,
    val provider: String,
    val displayName: String = "",
    val isEnabled: Boolean = true,
    val connectionState: String = "NOT_CONNECTED",
    val lastCheckedAtMillis: Long = 0
)

/** Mailbox candidates are NOT approved documents and do not carry permanent file paths. */
@Entity(
    tableName = "invoice_candidates",
    foreignKeys = [ForeignKey(
        entity = PurchaseOrderEntity::class,
        parentColumns = ["id"],
        childColumns = ["purchaseOrderId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("purchaseOrderId"),
        Index(value = ["purchaseOrderId", "accountEmail", "messageId", "attachmentId"], unique = true)
    ]
)
data class InvoiceCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseOrderId: Long,
    val accountEmail: String,
    val messageId: String,
    val attachmentId: String,
    val originalFileName: String,
    val mimeType: String = "application/pdf",
    val status: String = "PENDING_REVIEW",
    val foundAtMillis: Long = System.currentTimeMillis(),
    val reviewedAtMillis: Long = 0
)

@Entity(tableName = "purchases", indices = [Index("purchaseOrderId")])
data class PurchaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "0") val purchaseOrderId: Long = 0,
    val purchaseDateEpochDay: Long,
    val supplier: String,
    val item: String,
    val quantity: Int,
    val orderNumber: String = "",
    val accountUsername: String = "",
    val grossPence: Long,
    val netPence: Long,
    val vatPence: Long,
    val reverseVatPence: Long,
    val vatType: String,
    val paymentMethod: String = "",
    val status: String = "RECEIPT_PENDING",
    val receivedQty: Int = 0,
    val cancelledQty: Int = 0,
    val returnedQty: Int = 0,
    val refundExpectedPence: Long = 0,
    val refundReceivedPence: Long = 0,
    @ColumnInfo(defaultValue = "0") val partialRefund: Boolean = false,
    @ColumnInfo(defaultValue = "0") val refundNetPence: Long = 0,
    @ColumnInfo(defaultValue = "0") val refundVatPence: Long = 0,
    val invoicePath: String? = null,
    val notes: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "sales")
data class SaleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceNo: String,
    val saleDateEpochDay: Long,
    val customerId: Long,
    val vatType: String,
    val netPence: Long,
    val vatPence: Long,
    val grossPence: Long,
    val reverseVatPence: Long,
    val notes: String = "",
    val pdfPath: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "sale_lines", indices = [Index("saleId")])
data class SaleLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val item: String,
    val quantity: Int,
    val unitGrossPence: Long,
    val lineGrossPence: Long,
    val lineNetPence: Long,
    val lineVatPence: Long
)

@Entity(tableName = "sale_allocations", indices = [Index("saleLineId"), Index("purchaseId")])
data class SaleAllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleLineId: Long,
    val purchaseId: Long,
    val quantity: Int,
    val unitNetCostPence: Long
)

@Entity(
    tableName = "credit_notes",
    indices = [Index("saleId"), Index(value = ["creditNoteNo"], unique = true)]
)
data class CreditNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val creditNoteNo: String,
    val saleId: Long,
    val creditDateEpochDay: Long,
    val netPence: Long,
    val vatPence: Long,
    val grossPence: Long,
    val reverseVatPence: Long,
    val notes: String = "",
    val pdfPath: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "sale_returns", indices = [Index("saleLineId"), Index("creditNoteId")])
data class SaleReturnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleLineId: Long,
    @ColumnInfo(defaultValue = "0") val creditNoteId: Long = 0,
    val returnDateEpochDay: Long,
    val quantity: Int,
    val refundGrossPence: Long,
    val refundNetPence: Long,
    val refundVatPence: Long,
    val restock: Boolean,
    val notes: String = ""
)

@Entity(tableName = "sale_return_allocations", indices = [Index("saleReturnId"), Index("saleAllocationId")])
data class SaleReturnAllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleReturnId: Long,
    val saleAllocationId: Long,
    val quantity: Int
)

@Entity(
    tableName = "credit_note_imeis",
    indices = [Index("creditNoteId"), Index("saleReturnId"), Index("saleLineId")]
)
data class CreditNoteImeiEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val creditNoteId: Long,
    val saleReturnId: Long,
    val saleLineId: Long,
    val identifier: String,
    @ColumnInfo(defaultValue = "0") val purchaseId: Long = 0
)

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseDateEpochDay: Long,
    val supplier: String,
    val details: String,
    val account: String = "",
    val grossPence: Long,
    val netPence: Long,
    val vatPence: Long,
    val reverseVatPence: Long,
    val vatType: String,
    val paymentMethod: String = "",
    val attachmentPath: String? = null,
    val comments: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface AppDao {
    @Query("SELECT * FROM business WHERE id = 1") fun observeBusiness(): Flow<BusinessEntity?>
    @Query("SELECT * FROM business WHERE id = 1") suspend fun getBusiness(): BusinessEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveBusiness(value: BusinessEntity)

    @Query("SELECT * FROM accounting_periods ORDER BY startEpochDay DESC, id DESC") fun observeAccountingPeriods(): Flow<List<AccountingPeriodEntity>>
    @Query("SELECT * FROM accounting_periods ORDER BY startEpochDay ASC, id ASC") suspend fun getAccountingPeriods(): List<AccountingPeriodEntity>
    @Query("SELECT * FROM accounting_periods WHERE id = :id") suspend fun getAccountingPeriod(id: Long): AccountingPeriodEntity?
    @Insert suspend fun insertAccountingPeriod(value: AccountingPeriodEntity): Long
    @Update suspend fun updateAccountingPeriod(value: AccountingPeriodEntity)
    @Delete suspend fun deleteAccountingPeriod(value: AccountingPeriodEntity)

    @Query("SELECT * FROM customers ORDER BY companyName COLLATE NOCASE") fun observeCustomers(): Flow<List<CustomerEntity>>
    @Query("SELECT * FROM customers ORDER BY companyName COLLATE NOCASE") suspend fun getCustomers(): List<CustomerEntity>
    @Query("SELECT * FROM customers WHERE id = :id") suspend fun getCustomer(id: Long): CustomerEntity?
    @Insert suspend fun insertCustomer(value: CustomerEntity): Long
    @Update suspend fun updateCustomer(value: CustomerEntity)
    @Delete suspend fun deleteCustomer(value: CustomerEntity)

    @Query("SELECT * FROM purchase_orders ORDER BY purchaseDateEpochDay DESC, id DESC") fun observePurchaseOrders(): Flow<List<PurchaseOrderEntity>>
    @Query("SELECT * FROM purchase_orders ORDER BY purchaseDateEpochDay ASC, id ASC") suspend fun getPurchaseOrders(): List<PurchaseOrderEntity>
    @Query("SELECT * FROM purchase_orders WHERE id = :id") suspend fun getPurchaseOrder(id: Long): PurchaseOrderEntity?
    @Insert suspend fun insertPurchaseOrder(value: PurchaseOrderEntity): Long
    @Update suspend fun updatePurchaseOrder(value: PurchaseOrderEntity)
    @Delete suspend fun deletePurchaseOrder(value: PurchaseOrderEntity)

    @Query("SELECT COALESCE(MAX(invoiceSequence), 0) + 1 FROM purchase_orders WHERE purchaseDateEpochDay = :dateEpochDay AND invoiceSupplierKey = :supplierKey AND id != :excludeId")
    suspend fun nextPurchaseInvoiceSequence(dateEpochDay: Long, supplierKey: String, excludeId: Long = 0): Int

    @Query("SELECT * FROM purchase_invoices WHERE purchaseOrderId = :orderId ORDER BY invoiceOrdinal, id")
    fun observePurchaseInvoices(orderId: Long): Flow<List<PurchaseInvoiceEntity>>
    @Query("SELECT * FROM purchase_invoices ORDER BY purchaseOrderId, invoiceOrdinal, id")
    suspend fun getAllPurchaseInvoices(): List<PurchaseInvoiceEntity>
    @Query("SELECT * FROM purchase_invoices ORDER BY purchaseOrderId, invoiceOrdinal, id")
    fun observeAllPurchaseInvoices(): Flow<List<PurchaseInvoiceEntity>>
    @Query("SELECT * FROM purchase_invoices WHERE purchaseOrderId = :orderId ORDER BY invoiceOrdinal, id")
    suspend fun getPurchaseInvoices(orderId: Long): List<PurchaseInvoiceEntity>
    @Query("SELECT * FROM purchase_invoices WHERE id = :id")
    suspend fun getPurchaseInvoice(id: Long): PurchaseInvoiceEntity?
    @Query("SELECT * FROM purchase_invoices WHERE purchaseOrderId = :orderId AND filePath = :filePath LIMIT 1")
    suspend fun getPurchaseInvoiceByPath(orderId: Long, filePath: String): PurchaseInvoiceEntity?
    @Query("SELECT COALESCE(MAX(invoiceOrdinal), 0) + 1 FROM purchase_invoices WHERE purchaseOrderId = :orderId")
    suspend fun nextInvoiceOrdinal(orderId: Long): Int
    @Insert suspend fun insertPurchaseInvoice(value: PurchaseInvoiceEntity): Long
    @Update suspend fun updatePurchaseInvoice(value: PurchaseInvoiceEntity)
    @Delete suspend fun deletePurchaseInvoice(value: PurchaseInvoiceEntity)

    @Query("SELECT * FROM email_accounts ORDER BY emailAddress COLLATE NOCASE")
    fun observeEmailAccounts(): Flow<List<EmailAccountEntity>>
    @Query("SELECT * FROM email_accounts ORDER BY emailAddress COLLATE NOCASE")
    suspend fun getEmailAccounts(): List<EmailAccountEntity>
    @Insert suspend fun insertEmailAccount(value: EmailAccountEntity): Long
    @Update suspend fun updateEmailAccount(value: EmailAccountEntity)
    @Query("SELECT * FROM email_accounts WHERE lower(emailAddress) = lower(:email) LIMIT 1")
    suspend fun getEmailAccount(email: String): EmailAccountEntity?


    @Query("SELECT * FROM invoice_candidates WHERE purchaseOrderId = :orderId ORDER BY foundAtMillis DESC, id DESC")
    fun observeInvoiceCandidates(orderId: Long): Flow<List<InvoiceCandidateEntity>>
    @Query("SELECT * FROM invoice_candidates WHERE status = 'PENDING_REVIEW' ORDER BY foundAtMillis DESC, id DESC")
    fun observePendingInvoiceCandidates(): Flow<List<InvoiceCandidateEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertInvoiceCandidate(value: InvoiceCandidateEntity): Long
    @Update suspend fun updateInvoiceCandidate(value: InvoiceCandidateEntity)
    @Query("SELECT * FROM invoice_candidates WHERE purchaseOrderId = :orderId AND accountEmail = :email AND messageId = :messageId AND attachmentId = :attachmentId LIMIT 1")
    suspend fun findInvoiceCandidate(orderId: Long, email: String, messageId: String, attachmentId: String): InvoiceCandidateEntity?
    @Query("SELECT * FROM invoice_candidates WHERE id = :id LIMIT 1")
    suspend fun getInvoiceCandidate(id: Long): InvoiceCandidateEntity?
    @Query("DELETE FROM invoice_candidates WHERE purchaseOrderId = :orderId")
    suspend fun clearInvoiceCandidates(orderId: Long)
    @Query("SELECT * FROM invoice_candidates WHERE purchaseOrderId = :orderId AND status = 'PENDING_REVIEW'")
    suspend fun getPendingInvoiceCandidatesForOrder(orderId: Long): List<InvoiceCandidateEntity>
    @Query("DELETE FROM invoice_candidates WHERE purchaseOrderId = :orderId AND status = 'PENDING_REVIEW'")
    suspend fun clearPendingInvoiceCandidates(orderId: Long)

    @Query("SELECT * FROM purchases ORDER BY purchaseDateEpochDay DESC, id DESC") fun observePurchases(): Flow<List<PurchaseEntity>>
    @Query("SELECT * FROM purchases ORDER BY purchaseDateEpochDay ASC, id ASC") suspend fun getPurchases(): List<PurchaseEntity>
    @Query("SELECT * FROM purchases WHERE id = :id") suspend fun getPurchase(id: Long): PurchaseEntity?
    @Query("SELECT * FROM purchases WHERE purchaseOrderId = :orderId ORDER BY id") suspend fun getPurchasesForOrder(orderId: Long): List<PurchaseEntity>
    @Insert suspend fun insertPurchase(value: PurchaseEntity): Long
    @Update suspend fun updatePurchase(value: PurchaseEntity)
    @Delete suspend fun deletePurchase(value: PurchaseEntity)

    @Query("SELECT * FROM sales ORDER BY saleDateEpochDay DESC, id DESC") fun observeSales(): Flow<List<SaleEntity>>
    @Query("SELECT * FROM sales ORDER BY saleDateEpochDay ASC, id ASC") suspend fun getSales(): List<SaleEntity>
    @Query("SELECT * FROM sales WHERE id = :id") suspend fun getSale(id: Long): SaleEntity?
    @Query("SELECT COUNT(*) FROM sales WHERE customerId = :customerId AND saleDateEpochDay = :day") suspend fun countCustomerSalesOnDay(customerId: Long, day: Long): Int
    @Insert suspend fun insertSale(value: SaleEntity): Long
    @Update suspend fun updateSale(value: SaleEntity)

    @Query("SELECT * FROM sale_lines ORDER BY id") fun observeSaleLines(): Flow<List<SaleLineEntity>>
    @Query("SELECT * FROM sale_lines ORDER BY id") suspend fun getSaleLines(): List<SaleLineEntity>
    @Query("SELECT * FROM sale_lines WHERE saleId = :saleId ORDER BY id") suspend fun getSaleLinesForSale(saleId: Long): List<SaleLineEntity>
    @Insert suspend fun insertSaleLine(value: SaleLineEntity): Long
    @Query("DELETE FROM sale_lines WHERE saleId = :saleId") suspend fun deleteSaleLinesForSale(saleId: Long)

    @Query("SELECT * FROM sale_allocations ORDER BY id") fun observeSaleAllocations(): Flow<List<SaleAllocationEntity>>
    @Query("SELECT * FROM sale_allocations ORDER BY id") suspend fun getSaleAllocations(): List<SaleAllocationEntity>
    @Query("SELECT * FROM sale_allocations WHERE saleLineId IN (SELECT id FROM sale_lines WHERE saleId = :saleId)") suspend fun getAllocationsForSale(saleId: Long): List<SaleAllocationEntity>
    @Insert suspend fun insertSaleAllocation(value: SaleAllocationEntity): Long
    @Query("DELETE FROM sale_allocations WHERE saleLineId IN (SELECT id FROM sale_lines WHERE saleId = :saleId)") suspend fun deleteAllocationsForSale(saleId: Long)
    @Query("UPDATE sale_allocations SET unitNetCostPence = :unitCostPence WHERE purchaseId = :purchaseId") suspend fun updateAllocationUnitCostForPurchase(purchaseId: Long, unitCostPence: Long)

    @Query("SELECT * FROM credit_notes ORDER BY creditDateEpochDay DESC, id DESC") fun observeCreditNotes(): Flow<List<CreditNoteEntity>>
    @Query("SELECT * FROM credit_notes ORDER BY id") suspend fun getCreditNotes(): List<CreditNoteEntity>
    @Query("SELECT * FROM credit_notes WHERE id = :id") suspend fun getCreditNote(id: Long): CreditNoteEntity?
    @Query("SELECT * FROM credit_notes WHERE saleId = :saleId ORDER BY id") suspend fun getCreditNotesForSale(saleId: Long): List<CreditNoteEntity>
    @Insert suspend fun insertCreditNote(value: CreditNoteEntity): Long
    @Update suspend fun updateCreditNote(value: CreditNoteEntity)
    @Delete suspend fun deleteCreditNote(value: CreditNoteEntity)

    @Query("SELECT * FROM sale_returns ORDER BY returnDateEpochDay DESC, id DESC") fun observeSaleReturns(): Flow<List<SaleReturnEntity>>
    @Query("SELECT * FROM sale_returns ORDER BY id") suspend fun getSaleReturns(): List<SaleReturnEntity>
    @Query("SELECT * FROM sale_returns WHERE creditNoteId = :creditNoteId ORDER BY id") suspend fun getSaleReturnsForCreditNote(creditNoteId: Long): List<SaleReturnEntity>
    @Query("SELECT COUNT(*) FROM sale_returns WHERE saleLineId IN (SELECT id FROM sale_lines WHERE saleId = :saleId)") suspend fun countReturnsForSale(saleId: Long): Int
    @Insert suspend fun insertSaleReturn(value: SaleReturnEntity): Long
    @Query("DELETE FROM sale_returns WHERE creditNoteId = :creditNoteId") suspend fun deleteSaleReturnsForCreditNote(creditNoteId: Long)

    @Query("SELECT * FROM sale_return_allocations ORDER BY id") fun observeSaleReturnAllocations(): Flow<List<SaleReturnAllocationEntity>>
    @Query("SELECT * FROM sale_return_allocations ORDER BY id") suspend fun getSaleReturnAllocations(): List<SaleReturnAllocationEntity>
    @Insert suspend fun insertSaleReturnAllocation(value: SaleReturnAllocationEntity)
    @Query("DELETE FROM sale_return_allocations WHERE saleReturnId IN (SELECT id FROM sale_returns WHERE creditNoteId = :creditNoteId)") suspend fun deleteReturnAllocationsForCreditNote(creditNoteId: Long)

    @Query("SELECT * FROM credit_note_imeis ORDER BY id") fun observeCreditNoteImeis(): Flow<List<CreditNoteImeiEntity>>
    @Query("SELECT * FROM credit_note_imeis ORDER BY id") suspend fun getCreditNoteImeis(): List<CreditNoteImeiEntity>
    @Query("SELECT * FROM credit_note_imeis WHERE creditNoteId = :creditNoteId ORDER BY id") suspend fun getCreditNoteImeisForCreditNote(creditNoteId: Long): List<CreditNoteImeiEntity>
    @Insert suspend fun insertCreditNoteImei(value: CreditNoteImeiEntity): Long
    @Query("DELETE FROM credit_note_imeis WHERE creditNoteId = :creditNoteId") suspend fun deleteCreditNoteImeisForCreditNote(creditNoteId: Long)

    @Query("SELECT * FROM expenses ORDER BY expenseDateEpochDay DESC, id DESC") fun observeExpenses(): Flow<List<ExpenseEntity>>
    @Query("SELECT * FROM expenses ORDER BY expenseDateEpochDay ASC, id ASC") suspend fun getExpenses(): List<ExpenseEntity>
    @Query("SELECT * FROM expenses WHERE id = :id") suspend fun getExpense(id: Long): ExpenseEntity?
    @Insert suspend fun insertExpense(value: ExpenseEntity): Long
    @Update suspend fun updateExpense(value: ExpenseEntity)
}

@Database(
    entities = [
        BusinessEntity::class, AccountingPeriodEntity::class, CustomerEntity::class,
        PurchaseOrderEntity::class, PurchaseEntity::class, SaleEntity::class, SaleLineEntity::class,
        SaleAllocationEntity::class, CreditNoteEntity::class, SaleReturnEntity::class, SaleReturnAllocationEntity::class,
        CreditNoteImeiEntity::class, ExpenseEntity::class,
        PurchaseInvoiceEntity::class, EmailAccountEntity::class, InvoiceCandidateEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `purchase_orders` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `purchaseDateEpochDay` INTEGER NOT NULL,
                        `supplier` TEXT NOT NULL,
                        `orderNumber` TEXT NOT NULL,
                        `accountUsername` TEXT NOT NULL,
                        `vatType` TEXT NOT NULL,
                        `paymentMethod` TEXT NOT NULL,
                        `invoicePath` TEXT,
                        `notes` TEXT NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("ALTER TABLE `purchases` ADD COLUMN `purchaseOrderId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchases_purchaseOrderId` ON `purchases` (`purchaseOrderId`)")
                db.execSQL("""
                    INSERT OR IGNORE INTO `purchase_orders`
                    (`id`,`purchaseDateEpochDay`,`supplier`,`orderNumber`,`accountUsername`,`vatType`,`paymentMethod`,`invoicePath`,`notes`,`updatedAtMillis`)
                    SELECT `id`,`purchaseDateEpochDay`,`supplier`,`orderNumber`,`accountUsername`,`vatType`,`paymentMethod`,`invoicePath`,`notes`,`updatedAtMillis`
                    FROM `purchases`
                """.trimIndent())
                db.execSQL("UPDATE `purchases` SET `purchaseOrderId` = `id` WHERE `purchaseOrderId` = 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `purchases` ADD COLUMN `partialRefund` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `purchases` ADD COLUMN `refundNetPence` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `purchases` ADD COLUMN `refundVatPence` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `purchases` SET `refundNetPence` = CASE WHEN `refundExpectedPence` > 0 AND `vatType` = 'STANDARD' THEN CAST(ROUND(`refundExpectedPence` / 1.2) AS INTEGER) WHEN `refundExpectedPence` > 0 THEN `refundExpectedPence` ELSE 0 END")
                db.execSQL("UPDATE `purchases` SET `refundVatPence` = CASE WHEN `refundExpectedPence` > 0 AND `vatType` = 'STANDARD' THEN `refundExpectedPence` - `refundNetPence` ELSE 0 END")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `business` ADD COLUMN `selectedAccountingPeriodId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `accounting_periods` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `startEpochDay` INTEGER NOT NULL,
                        `endEpochDay` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO `accounting_periods`
                    (`id`,`name`,`startEpochDay`,`endEpochDay`,`createdAtMillis`)
                    SELECT 1,'Existing accounting period',`accountingStartEpochDay`,`accountingEndEpochDay`,CAST(strftime('%s','now') AS INTEGER) * 1000
                    FROM `business`
                    WHERE `id` = 1 AND `accountingStartEpochDay` > 0 AND `accountingEndEpochDay` >= `accountingStartEpochDay`
                """.trimIndent())
                db.execSQL("UPDATE `business` SET `selectedAccountingPeriodId` = CASE WHEN EXISTS(SELECT 1 FROM `accounting_periods` WHERE `id` = 1) THEN 1 ELSE 0 END WHERE `id` = 1")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `credit_notes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `creditNoteNo` TEXT NOT NULL,
                        `saleId` INTEGER NOT NULL,
                        `creditDateEpochDay` INTEGER NOT NULL,
                        `netPence` INTEGER NOT NULL,
                        `vatPence` INTEGER NOT NULL,
                        `grossPence` INTEGER NOT NULL,
                        `reverseVatPence` INTEGER NOT NULL,
                        `notes` TEXT NOT NULL,
                        `pdfPath` TEXT,
                        `updatedAtMillis` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_notes_saleId` ON `credit_notes` (`saleId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_credit_notes_creditNoteNo` ON `credit_notes` (`creditNoteNo`)")
                db.execSQL("ALTER TABLE `sale_returns` ADD COLUMN `creditNoteId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_returns_creditNoteId` ON `sale_returns` (`creditNoteId`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `credit_note_imeis` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `creditNoteId` INTEGER NOT NULL,
                        `saleReturnId` INTEGER NOT NULL,
                        `saleLineId` INTEGER NOT NULL,
                        `identifier` TEXT NOT NULL,
                        `purchaseId` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_note_imeis_creditNoteId` ON `credit_note_imeis` (`creditNoteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_note_imeis_saleReturnId` ON `credit_note_imeis` (`saleReturnId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_note_imeis_saleLineId` ON `credit_note_imeis` (`saleLineId`)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `business` ADD COLUMN `invoiceIdentifier` TEXT NOT NULL DEFAULT ''")
            }
        }


        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `purchase_orders` ADD COLUMN `invoiceSequence` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `purchase_orders` ADD COLUMN `invoiceSupplierKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE purchase_orders SET invoiceSupplierKey = lower(trim(supplier))")
                // Correlated count works on Android's bundled SQLite (no window-function dependency).
                db.execSQL("""
                    UPDATE purchase_orders SET invoiceSequence = (
                        SELECT COUNT(*) FROM purchase_orders AS earlier
                        WHERE earlier.purchaseDateEpochDay = purchase_orders.purchaseDateEpochDay
                          AND earlier.invoiceSupplierKey = purchase_orders.invoiceSupplierKey
                          AND earlier.id <= purchase_orders.id
                    )
                """.trimIndent())

                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_purchase_orders_purchaseDateEpochDay_invoiceSupplierKey_invoiceSequence` ON `purchase_orders` (`purchaseDateEpochDay`, `invoiceSupplierKey`, `invoiceSequence`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `purchase_invoices` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `purchaseOrderId` INTEGER NOT NULL,
                        `invoiceOrdinal` INTEGER NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `originalFileName` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `sourceAccountEmail` TEXT NOT NULL,
                        `sourceMessageId` TEXT,
                        `sourceAttachmentId` TEXT,
                        `sha256` TEXT NOT NULL,
                        `byteSize` INTEGER NOT NULL,
                        `approvedAtMillis` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        FOREIGN KEY(`purchaseOrderId`) REFERENCES `purchase_orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_invoices_purchaseOrderId` ON `purchase_invoices` (`purchaseOrderId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_purchase_invoices_purchaseOrderId_invoiceOrdinal` ON `purchase_invoices` (`purchaseOrderId`, `invoiceOrdinal`)")
                // Backfill without moving or deleting the old local files; purchase header is authoritative.
                // Fallback to the first line for older databases that only held the path there.
                db.execSQL("""
                    INSERT INTO purchase_invoices
                        (purchaseOrderId, invoiceOrdinal, filePath, originalFileName, source,
                         sourceAccountEmail, sourceMessageId, sourceAttachmentId, sha256, byteSize,
                         approvedAtMillis, createdAtMillis)
                    SELECT po.id, 1,
                        CASE WHEN length(trim(coalesce(po.invoicePath, ''))) > 0 THEN po.invoicePath
                             ELSE (SELECT p.invoicePath FROM purchases p
                                   WHERE p.purchaseOrderId = po.id AND length(trim(coalesce(p.invoicePath, ''))) > 0
                                   ORDER BY p.id LIMIT 1) END,
                        '', 'LEGACY', '', NULL, NULL, '', 0,
                        po.updatedAtMillis, po.updatedAtMillis
                    FROM purchase_orders po
                    WHERE length(trim(coalesce(po.invoicePath, ''))) > 0 OR EXISTS (
                        SELECT 1 FROM purchases p WHERE p.purchaseOrderId = po.id
                          AND length(trim(coalesce(p.invoicePath, ''))) > 0
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `email_accounts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `emailAddress` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL,
                        `connectionState` TEXT NOT NULL,
                        `lastCheckedAtMillis` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_email_accounts_emailAddress` ON `email_accounts` (`emailAddress`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `invoice_candidates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `purchaseOrderId` INTEGER NOT NULL,
                        `accountEmail` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `attachmentId` TEXT NOT NULL,
                        `originalFileName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `foundAtMillis` INTEGER NOT NULL,
                        `reviewedAtMillis` INTEGER NOT NULL,
                        FOREIGN KEY(`purchaseOrderId`) REFERENCES `purchase_orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_candidates_purchaseOrderId` ON `invoice_candidates` (`purchaseOrderId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_invoice_candidates_purchaseOrderId_accountEmail_messageId_attachmentId` ON `invoice_candidates` (`purchaseOrderId`, `accountEmail`, `messageId`, `attachmentId`)")
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "purchase-sales.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
    }
}
