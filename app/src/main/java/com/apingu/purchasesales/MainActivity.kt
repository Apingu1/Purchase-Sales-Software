package com.apingu.purchasesales

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.apingu.purchasesales.data.AppDatabase
import com.apingu.purchasesales.data.AppRepository
import com.apingu.purchasesales.ui.PurchaseSalesRootV2

class PurchaseSalesApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    val repository by lazy { AppRepository(this, database) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PurchaseSalesRootV2() }
    }
}
