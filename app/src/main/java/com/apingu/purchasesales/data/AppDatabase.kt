package com.apingu.purchasesales.data

import android.content.Context
import androidx.room.*
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

@Entity(tableName = "purchases")
data class PurchaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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

    @Query("SELECT * FROM purchases ORDER BY purchaseDateEpochDay DESC, id DESC") fun observePurchases(): Flow<List<PurchaseEntity>>
    @Query("SELECT * FROM purchases ORDER BY purchaseDateEpochDay ASC, id ASC") suspend fun getPurchases(): List<PurchaseEntity>
    @Query("SELECT * FROM purchases WHERE id = :id") suspend fun getPurchase(id: Long): PurchaseEntity?
    @Insert suspend fun insertPurchase(value: PurchaseEntity): Long
    @Update suspend fun updatePurchase(value: PurchaseEntity)

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
        BusinessEntity::class, CustomerEntity::class, PurchaseEntity::class,
        SaleEntity::class, SaleLineEntity::class, SaleAllocationEntity::class,
        SaleReturnEntity::class, SaleReturnAllocationEntity::class, ExpenseEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "purchase-sales.db"
        ).fallbackToDestructiveMigration().build()
    }
}
