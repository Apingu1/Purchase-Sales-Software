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
    val dropboxAutoSync: Boolean = false
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

/** One supplier order header can contain any number of independently tracked purchase lines. */
@Entity(tableName = "purchase_orders")
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
    val updatedAtMillis: Long = System.currentTimeMillis()
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

@Entity(tableName = "sale_returns", indices = [Index("saleLineId")])
data class SaleReturnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleLineId: Long,
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

    @Query("SELECT * FROM sale_returns ORDER BY returnDateEpochDay DESC, id DESC") fun observeSaleReturns(): Flow<List<SaleReturnEntity>>
    @Query("SELECT * FROM sale_returns ORDER BY id") suspend fun getSaleReturns(): List<SaleReturnEntity>
    @Query("SELECT COUNT(*) FROM sale_returns WHERE saleLineId IN (SELECT id FROM sale_lines WHERE saleId = :saleId)") suspend fun countReturnsForSale(saleId: Long): Int
    @Insert suspend fun insertSaleReturn(value: SaleReturnEntity): Long

    @Query("SELECT * FROM sale_return_allocations ORDER BY id") fun observeSaleReturnAllocations(): Flow<List<SaleReturnAllocationEntity>>
    @Query("SELECT * FROM sale_return_allocations ORDER BY id") suspend fun getSaleReturnAllocations(): List<SaleReturnAllocationEntity>
    @Insert suspend fun insertSaleReturnAllocation(value: SaleReturnAllocationEntity): Long

    @Query("SELECT * FROM expenses ORDER BY expenseDateEpochDay DESC, id DESC") fun observeExpenses(): Flow<List<ExpenseEntity>>
    @Query("SELECT * FROM expenses ORDER BY expenseDateEpochDay ASC, id ASC") suspend fun getExpenses(): List<ExpenseEntity>
    @Query("SELECT * FROM expenses WHERE id = :id") suspend fun getExpense(id: Long): ExpenseEntity?
    @Insert suspend fun insertExpense(value: ExpenseEntity): Long
    @Update suspend fun updateExpense(value: ExpenseEntity)
}

@Database(
    entities = [
        BusinessEntity::class, CustomerEntity::class, PurchaseOrderEntity::class, PurchaseEntity::class,
        SaleEntity::class, SaleLineEntity::class, SaleAllocationEntity::class,
        SaleReturnEntity::class, SaleReturnAllocationEntity::class, ExpenseEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
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
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `purchases` ADD COLUMN `purchaseOrderId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchases_purchaseOrderId` ON `purchases` (`purchaseOrderId`)")
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `purchase_orders`
                    (`id`,`purchaseDateEpochDay`,`supplier`,`orderNumber`,`accountUsername`,`vatType`,`paymentMethod`,`invoicePath`,`notes`,`updatedAtMillis`)
                    SELECT `id`,`purchaseDateEpochDay`,`supplier`,`orderNumber`,`accountUsername`,`vatType`,`paymentMethod`,`invoicePath`,`notes`,`updatedAtMillis`
                    FROM `purchases`
                    """.trimIndent()
                )
                db.execSQL("UPDATE `purchases` SET `purchaseOrderId` = `id` WHERE `purchaseOrderId` = 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `purchases` ADD COLUMN `partialRefund` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `purchases` ADD COLUMN `refundNetPence` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `purchases` ADD COLUMN `refundVatPence` INTEGER NOT NULL DEFAULT 0")
                // Existing V2 refund records used an automatic gross split. Preserve that legacy result;
                // users can edit the new explicit Net/VAT breakdown afterwards where the supplier credit differs.
                db.execSQL("UPDATE `purchases` SET `refundNetPence` = CASE WHEN `refundExpectedPence` > 0 AND `vatType` = 'STANDARD' THEN CAST(ROUND(`refundExpectedPence` / 1.2) AS INTEGER) WHEN `refundExpectedPence` > 0 THEN `refundExpectedPence` ELSE 0 END")
                db.execSQL("UPDATE `purchases` SET `refundVatPence` = CASE WHEN `refundExpectedPence` > 0 AND `vatType` = 'STANDARD' THEN `refundExpectedPence` - `refundNetPence` ELSE 0 END")
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "purchase-sales.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).fallbackToDestructiveMigration().build()
    }
}
