package com.example.speech_to_text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack // autoMirrored import
import androidx.compose.material3.* // Material 3 importları
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue



@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class) // TopAppBar için gerekli olabilir
@Composable
fun HistoryScreen(navController: NavController, viewModel: MainActivity.MainViewModel) {
    val savedTexts = viewModel.savedTexts // ViewModel'den listeyi al

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kaydedilen Metinler") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) { // Geri gitmek için
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack, // Geri oku ikonu
                            contentDescription = "Geri"
                        )
                    }
                },
                // Opsiyonel: Renkleri ayarlayabilirsiniz
                // colors = TopAppBarDefaults.topAppBarColors(
                //     containerColor = MaterialTheme.colorScheme.primary,
                //     titleContentColor = MaterialTheme.colorScheme.onPrimary,
                //     navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                // )
            )
        }
    ) { paddingValues -> // Scaffold içeriğinin padding'ini uygula
        if (savedTexts.isEmpty()) {
            // Liste boşsa mesaj göster
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues), // Scaffold padding'ini uygula
                contentAlignment = Alignment.Center
            ) {
                Text("Henüz kaydedilmiş metin yok.")
            }
        } else {
            // Liste doluysa LazyColumn ile göster
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues) // Scaffold padding'ini uygula
                    .padding(horizontal = 16.dp) // Ekstra yan boşluklar
            ) {
                items(
                    items = savedTexts,
                    key = {text -> text}) { text ->

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItemPlacement()
                            .padding(horizontal = 16.dp, vertical = 8.dp), // Satır içi boşluklar
                        verticalAlignment = Alignment.CenterVertically // Dikeyde ortala
                    ) {
                        Text(
                            text = text,
                            modifier = Modifier.weight(1f), // Öğeler arası dikey boşluk
                            style = MaterialTheme.typography.bodyLarge // Metin stili
                        )
                        IconButton(
                            onClick = {
                                // ViewModel'deki silme fonksiyonunu çağır
                                viewModel.deleteSavedText(text)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Sil", // Erişilebilirlik için
                                tint = MaterialTheme.colorScheme.error // İkon rengini kırmızı yapalım
                            )
                        }
                    }
                        HorizontalDivider(thickness = 0.5.dp) // Öğeler arasına ince çizgi
                    }
                }
            }
        }
    }
