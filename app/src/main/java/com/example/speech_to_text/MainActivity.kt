package com.example.speech_to_text

import android.Manifest
import android.app.Application // ViewModel için eklendi
import android.content.Context // SharedPreferences için eklendi
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
// import androidx.lifecycle.viewmodel.compose.viewModel // Bu import yerine by viewModels kullanılıyor
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
// import org.vosk.android.StorageService // Kullanılmıyor
import java.io.IOException
import java.util.Locale
import android.speech.RecognitionListener as GoogleRecognitionListener // Alias
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel // ViewModel'den AndroidViewModel'e değiştirildi
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import androidx.compose.runtime.mutableStateListOf
// import androidx.compose.runtime.mutableStateOf // Zaten yukarıda var
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.json.JSONArray // SharedPreferences için eklendi
import org.json.JSONException // SharedPreferences için eklendi


class MainActivity : ComponentActivity() {

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var currentModelPath: String? = null // Takip etmek için hangi modelin yüklü olduğunu tutalım
    private var voskRecognizer: Recognizer? = null // Recognizer'ı da üye değişken yapalım
    private var googleSpeechRecognizer: SpeechRecognizer? = null // Google için


    // ViewModel'i Activity kapsamında oluştur
    private val viewModel: MainViewModel by viewModels()

    // İzin İsteği için ActivityResultLauncher
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                // İzin verildiyse, başlangıçta seçili olan Vosk modelini yükle
                if (viewModel.isVoskSelected.value) {
                    initModel()
                }
                // Google seçiliyse özel bir başlatma gerekmez.
            } else {
                Toast.makeText(this, "Mikrofon izni gerekiyor!", Toast.LENGTH_SHORT).show()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LibVosk.setLogLevel(LogLevel.INFO)
        checkRecordAudioPermission() // İzin isteme/kontrol etme

        setContent {
            // Activity'nin viewModel'ini Composable'a ver
            val navController = rememberNavController()
            MaterialTheme { // Veya kendi tema adınız
                NavHost(navController = navController, startDestination = "main") {
                    // Ana Ekran Route'u
                    composable("main") {
                        SpeechToTextApp(
                            viewModel = this@MainActivity.viewModel,
                            navController = navController,
                            activity = this@MainActivity // Activity referansını geçiyoruz
                        )
                    }
                    // Geçmiş Ekranı Route'u
                    composable("history") {
                        HistoryScreen(
                            navController = navController,
                            viewModel = this@MainActivity.viewModel
                        )
                    }
                }
            }

        }
    }

    private fun checkRecordAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // İzin zaten var. Başlangıçta Vosk seçili ise modelini yükle.
                if (viewModel.isVoskSelected.value) {
                    initModel()
                }
            }
            else -> {
                // İzin iste
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // --- Kaynak Temizleme Fonksiyonları ---
    private fun releaseVoskResources() {
        Log.d("VoskCleanup", "Releasing Vosk resources...")
        speechService?.stop()
        speechService?.shutdown()
        voskRecognizer?.close() // Recognizer Model'den önce kapatılmalı
        model?.close() // Model (native kaynaklar)
        speechService = null
        voskRecognizer = null
        model = null
        currentModelPath = null
        Log.d("VoskCleanup", "Vosk resources released.")
    }

    private fun releaseGoogleRecognizer() {
        Log.d("GoogleCleanup", "Releasing Google recognizer...")
        googleSpeechRecognizer?.stopListening() // Önce dinlemeyi durdur
        googleSpeechRecognizer?.cancel() // Bekleyen işlemleri iptal et
        googleSpeechRecognizer?.destroy() // Kaynakları serbest bırak
        googleSpeechRecognizer = null
        Log.d("GoogleCleanup", "Google recognizer released.")
    }

    private fun releaseAllRecognizers() {
        Log.d("Cleanup", "Releasing ALL recognizers...")
        releaseVoskResources()
        releaseGoogleRecognizer()
        Log.d("Cleanup", "ALL recognizers released.")
    }
    // --- Bitiş: Kaynak Temizleme Fonksiyonları ---

    private fun initModel() {
        // Hedeflenen model yolunu belirle
        val targetModelDirName = if (viewModel.isTurkishSelected.value) {
            "vosk-model-small-tr-0.3" // Türkçe model klasör adı
        } else {
            "vosk-model-small-en-us-0.15" // İngilizce model klasör adı
        }
        val modelsBaseDir = File(filesDir, "models")
        val targetExtractedModelDir = File(modelsBaseDir, targetModelDirName)
        val targetModelPath = targetExtractedModelDir.absolutePath

        Log.d("VoskInit", "Target model path: $targetModelPath")
        Log.d("VoskInit", "Current model path: $currentModelPath")

        // 1. Farklı bir model mi gerekiyor? Veya hiç model yüklü değil mi?
        if (model == null || currentModelPath != targetModelPath) {
            Log.d("VoskInit", "Model change detected or no model loaded. Releasing old resources.")
            releaseVoskResources() // Önce mevcut Vosk kaynaklarını temizle

            // 2. Gerekli model dosyaları var mı? Yoksa çıkar.
            if (!targetExtractedModelDir.exists()) {
                Log.d("VoskInit", "Model directory does not exist. Extracting...")
                val modelZipName = "$targetModelDirName.zip"
                val modelZipFile = File(modelsBaseDir, modelZipName)

                // Gerekli dizini oluştur
                if (!modelsBaseDir.exists()) {
                    modelsBaseDir.mkdirs()
                    Log.d("VoskInit", "Created models base directory: ${modelsBaseDir.absolutePath}")
                }

                try {
                    // ZIP'i assets'ten kopyala
                    Log.d("VoskInit", "Copying $modelZipName from assets...")
                    assets.open(modelZipName).use { inputStream ->
                        FileOutputStream(modelZipFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    Log.d("VoskInit", "$modelZipName copied successfully.")

                    // ZIP'i aç
                    Log.d("VoskInit", "Extracting $modelZipName...")
                    ZipInputStream(FileInputStream(modelZipFile)).use { zipInputStream ->
                        var entry = zipInputStream.nextEntry
                        while (entry != null) {
                            val filePath = File(modelsBaseDir, entry.name) // modelsBaseDir'e aç
                            if (entry.isDirectory) {
                                if (!filePath.exists()) filePath.mkdirs()
                            } else {
                                filePath.parentFile?.mkdirs() // Üst dizinleri oluştur
                                FileOutputStream(filePath).use { fileOutputStream ->
                                    zipInputStream.copyTo(fileOutputStream)
                                }
                            }
                            zipInputStream.closeEntry()
                            entry = zipInputStream.nextEntry
                        }
                    }
                    Log.d("VoskInit", "$modelZipName extracted successfully to ${targetExtractedModelDir.absolutePath}")

                    // ZIP dosyasını sil
                    modelZipFile.delete()
                    Log.d("VoskInit", "$modelZipName deleted.")

                } catch (e: FileNotFoundException) {
                    Log.e("VoskInit", "Model ZIP '$modelZipName' not found in assets!", e)
                    Toast.makeText(this, "Model dosyası '$modelZipName' bulunamadı!", Toast.LENGTH_LONG).show()
                    return
                } catch (e: IOException) {
                    Log.e("VoskInit", "Error copying/extracting model", e)
                    Toast.makeText(this, "Model kopyalanamadı/açılamadı: ${e.message}", Toast.LENGTH_LONG).show()
                    return
                }
            } else {
                Log.d("VoskInit", "Model directory already exists: ${targetExtractedModelDir.absolutePath}")
            }

            // 3. Model nesnesini oluştur
            try {
                Log.d("VoskInit", "Loading Model from: $targetModelPath")
                model = Model(targetModelPath)
                currentModelPath = targetModelPath // Yüklenen modelin yolunu sakla
                Log.d("VoskInit", "Model loaded successfully: $currentModelPath")

                // 4. Recognizer ve SpeechService'i oluştur
                Log.d("VoskInit", "Creating Recognizer and SpeechService...")
                voskRecognizer = Recognizer(model, 16000.0f)
                speechService = SpeechService(voskRecognizer, 16000.0f)
                Log.d("VoskInit", "Recognizer and SpeechService created.")

            } catch (e: IOException) {
                Log.e("VoskInit", "Failed to load Model or create Recognizer/SpeechService", e)
                Toast.makeText(this, "Vosk modeli yüklenemedi: ${e.message}", Toast.LENGTH_LONG).show()
                releaseVoskResources() // Hata olursa temizle
            }
        } else {
            Log.d("VoskInit", "Required model ($targetModelPath) is already loaded.")
            // Model zaten yüklü, Recognizer/Service null mı kontrol et (beklenmedik durum)
            if (voskRecognizer == null || speechService == null) {
                Log.w("VoskInit", "Model is loaded but Recognizer/Service is null. Recreating.")
                try {
                    voskRecognizer = Recognizer(model, 16000.0f)
                    speechService = SpeechService(voskRecognizer, 16000.0f)
                    Log.d("VoskInit", "Recognizer and SpeechService recreated.")
                } catch (e: IOException) {
                    Log.e("VoskInit", "Failed to recreate Recognizer/SpeechService", e)
                    Toast.makeText(this, "Vosk yeniden başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
                    releaseVoskResources()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy called. Releasing all resources.")
        releaseAllRecognizers() // Uygulama kapanırken tüm kaynakları serbest bırak
    }

    // --- ViewModel Tanımı (MainActivity içinde) ---
    // Daha iyi pratik: Bu sınıfı ayrı bir Kotlin dosyasına taşımak.
    class MainViewModel(application: Application) : AndroidViewModel(application) { // ViewModel'den AndroidViewModel'e değiştirildi ve application parametresi eklendi
        private val _recognizedText = mutableStateOf("")
        val recognizedText: State<String> = _recognizedText

        private val _isVoskSelected = mutableStateOf(true)
        val isVoskSelected: State<Boolean> = _isVoskSelected

        private val _isTurkishSelected = mutableStateOf(true) // Vosk dili için
        val isTurkishSelected: State<Boolean> = _isTurkishSelected

        // SharedPreferences için sabitler
        companion object {
            private const val PREFS_NAME = "SpeechAppPrefs"
            private const val KEY_SAVED_TEXTS = "savedTextsKey"
        }

        private val sharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // _savedTexts'i SharedPreferences'dan yükleyerek başlat
        private val _savedTexts = mutableStateListOf<String>()
        val savedTexts: List<String> = _savedTexts // HistoryScreen'in gözlemleyeceği liste

        init {
            _savedTexts.addAll(loadTextsFromPrefs())
        }


        private fun saveTextsToPrefs() {
            try {
                val jsonArray = JSONArray()
                _savedTexts.forEach { jsonArray.put(it) }
                sharedPreferences.edit().putString(KEY_SAVED_TEXTS, jsonArray.toString()).apply()
                Log.d("ViewModelPrefs", "Texts saved to SharedPreferences: $jsonArray")
            } catch (e: JSONException) {
                Log.e("ViewModelPrefs", "Error saving texts to SharedPreferences", e)
            }
        }

        private fun loadTextsFromPrefs(): MutableList<String> {
            val jsonString = sharedPreferences.getString(KEY_SAVED_TEXTS, null)
            val texts = mutableListOf<String>()
            if (jsonString != null) {
                try {
                    val jsonArray = JSONArray(jsonString)
                    for (i in 0 until jsonArray.length()) {
                        texts.add(jsonArray.getString(i))
                    }
                    Log.d("ViewModelPrefs", "Texts loaded from SharedPreferences: $texts")
                } catch (e: JSONException) {
                    Log.e("ViewModelPrefs", "Error parsing saved texts from SharedPreferences", e)
                    // Hata durumunda boş liste döndürülür veya eski veriler silinebilir
                    // sharedPreferences.edit().remove(KEY_SAVED_TEXTS).apply()
                }
            } else {
                Log.d("ViewModelPrefs", "No saved texts found in SharedPreferences.")
            }
            return texts
        }


        fun setRecognizedText(text: String) {
            _recognizedText.value = text
        }

        fun toggleVoskSelection() {
            val newSelection = !_isVoskSelected.value
            _isVoskSelected.value = newSelection
            clearText()
        }

        fun setTurkish() {
            if (_isVoskSelected.value) {
                if (!_isTurkishSelected.value) {
                    _isTurkishSelected.value = true
                    clearText()
                }
            }
        }

        fun setEnglish() {
            if (_isVoskSelected.value) {
                if (_isTurkishSelected.value) {
                    _isTurkishSelected.value = false
                    clearText()
                }
            }
        }

        fun clearText() {
            _recognizedText.value = ""
        }
        fun saveCurrentText() {
            val textToSave = recognizedText.value.trim()
            if(textToSave.isNotEmpty()){
                if(!_savedTexts.contains(textToSave)){
                    _savedTexts.add(0,textToSave) // Başa ekle
                    Log.d("ViewModel", "Text saved: '$textToSave'. Total saved: ${_savedTexts.size}")
                    saveTextsToPrefs() // SharedPreferences'a kaydet
                } else {
                    Log.d("ViewModel", "Text '$textToSave' already exists in saved list.")
                }
            }
        }

        fun deleteSavedText(textToDelete: String) {
            val removed = _savedTexts.remove(textToDelete)
            if (removed) {
                Log.d("ViewModel", "Text deleted: '$textToDelete'. Remaining: ${_savedTexts.size}")
                saveTextsToPrefs() // SharedPreferences'a kaydet
            } else {
                Log.d("ViewModel", "Attempted to delete text not found: '$textToDelete'")
            }
        }
    }
    // --- Bitiş: ViewModel Tanımı ---


    // --- Composable UI Tanımı ---
    @Composable
    fun SpeechToTextApp(viewModel: MainViewModel,navController: NavController, activity: MainActivity) {
        val recognizedText by viewModel.recognizedText
        val isVoskSelected by viewModel.isVoskSelected
        val isTurkishSelected by viewModel.isTurkishSelected // Vosk dilini buradan alacağız
        // val activity = LocalContext.current as MainActivity // Parametre olarak alındığı için gerek yok

        MaterialTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Tanınan metin alanı
                Text(
                    text = recognizedText.ifBlank { "Dinlemeye başlamak için 'Konuşmayı Başlat' düğmesine basın." },
                    modifier = Modifier
                        .weight(1f) // Alanın çoğunu kaplaması için
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp)
                )

                Spacer(modifier = Modifier.height(8.dp)) // Metin ile butonlar arasına boşluk

                // --- Kontrol Butonları ---
                Column(horizontalAlignment = Alignment.CenterHorizontally) {

                    // Konuşmayı Başlat Butonu
                    Button(
                        onClick = {
                            if (isVoskSelected) {
                                activity.releaseGoogleRecognizer() // Google açıksa kapat (önlem)
                                activity.startVoskListening(viewModel::setRecognizedText)
                            } else {
                                activity.releaseVoskResources() // Vosk açıksa kapat (önlem)
                                activity.startGoogleListening(viewModel::setRecognizedText)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Text("Konuşmayı Başlat")
                    }

                    // Vosk/Google Değiştirme Butonu
                    Button(
                        onClick = {
                            activity.releaseAllRecognizers() // Önce temizle
                            viewModel.toggleVoskSelection() // Sonra state'i değiştir
                            // Yeni seçime göre modeli hazırla (sadece Vosk ise)
                            if (viewModel.isVoskSelected.value) {
                                activity.initModel()
                            }
                            // clearText() viewModel içinde zaten çağrılıyor
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Text(if (isVoskSelected) "Geçiş Yap: Google Kullan" else "Geçiş Yap: Vosk Kullan")
                    }

                    // Dil ve Temizle Butonları için Ortak Satır
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween // Butonları iki uca yasla
                    ) {
                        // Dil Değiştirme Butonu (Sadece Vosk seçiliyken aktif ve görünür)
                        Button(
                            onClick = {
                                // Mevcut dilin tersini ayarla
                                if (isTurkishSelected) {
                                    Log.d("UIAction", "Switching Vosk to English from Button")
                                    viewModel.setEnglish()
                                } else {
                                    Log.d("UIAction", "Switching Vosk to Turkish from Button")
                                    viewModel.setTurkish()
                                }
                                // Modeli yeniden yükle
                                activity.initModel()
                                // clearText() viewModel içinde zaten çağrılıyor
                            },
                            enabled = isVoskSelected // Sadece Vosk seçiliyken etkin
                            // modifier = Modifier.weight(1f) // Eşit genişlik istersen
                        ) {
                            // Seçili dile göre metni göster (Vosk seçili değilse de pasif görünür)
                            Text(
                                if (isVoskSelected) {
                                    if (isTurkishSelected) "Dil: Türkçe" else "Dil: English"
                                } else {
                                    "Dil (Vosk)" // Vosk seçili değilken gösterilecek metin
                                }
                            )
                        }

                        Button(
                            onClick = {
                                viewModel.saveCurrentText() // Metni ViewModel üzerinden kaydet
                                // SharedPreferences'a kaydetme viewModel içinde yapılacak
                                if (viewModel.recognizedText.value.isNotBlank()) { // Sadece doluysa geçmiş ekranına git
                                    navController.navigate("history")
                                } else {
                                    Toast.makeText(activity, "Kaydedilecek metin yok.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = recognizedText.isNotBlank() // Metin boş değilse aktif
                        ) {
                            Text("Kaydet")
                        }


                        // Temizle Butonu (Her zaman görünür ve aktif)
                        Button(
                            onClick = { viewModel.clearText() }
                        ) {
                            Text("Temizle")
                        }
                    }
                }
            }
        }
    }
    // --- Bitiş: Composable UI Tanımı ---


    // --- Vosk Dinleme Fonksiyonları ---
    private fun startVoskListening(onResult: (String) -> Unit) {
        // Modelin ve servisin hazır olduğundan emin ol
        if (model == null || voskRecognizer == null || speechService == null) {
            Log.w("VoskListen", "Vosk components not ready. Attempting to initialize...")
            initModel() // Tekrar başlatmayı dene
            // Tekrar kontrol et
            if (model == null || voskRecognizer == null || speechService == null) {
                Toast.makeText(this, "Vosk başlatılamadı. Model yüklenemedi.", Toast.LENGTH_SHORT).show()
                Log.e("VoskListen", "Cannot start listening. Vosk components still null after re-init.")
                return
            }
            Log.d("VoskListen", "Vosk components initialized successfully on second attempt.")
        }

        try {
            Log.d("VoskListen", "Starting Vosk listening...")
            speechService?.stop()

            speechService?.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String) {
                    processVoskResult(hypothesis, isFinal = false, onResult)
                }

                override fun onResult(hypothesis: String) {
                    processVoskResult(hypothesis, isFinal = true, onResult)
                    Log.d("VoskListen", "Vosk onResult received. Listening implicitly stops or waits for next utterance.")
                }

                override fun onFinalResult(hypothesis: String) {
                    Log.d("VoskListen", "Vosk onFinalResult: $hypothesis")
                    processVoskResult(hypothesis, isFinal = true, onResult)
                }

                override fun onError(error: Exception) {
                    Log.e("VoskListen", "Vosk Error", error)
                    updateText("Vosk Hatası: ${error.message}")
                }

                override fun onTimeout() {
                    Log.w("VoskListen", "Vosk Timeout")
                    updateText("Vosk Zaman Aşımı")
                }
            })
            updateText("Vosk dinliyor...") // UI'da dinlediğini belirt
            Log.d("VoskListen", "Vosk startListening called.")

        } catch (e: IOException) {
            Log.e("VoskListen", "IOException during startListening", e)
            Toast.makeText(this, "Vosk dinleme başlatılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
            releaseVoskResources() // Sorun varsa temizle
        } catch (e: IllegalStateException) {
            Log.e("VoskListen", "IllegalStateException during startListening", e)
            Toast.makeText(this, "Vosk durumu hatası: ${e.message}", Toast.LENGTH_SHORT).show()
            releaseVoskResources() // Sorun varsa temizle
        }
    }

    private fun processVoskResult(jsonResult: String?, isFinal: Boolean, onResult: (String) -> Unit) {
        if (jsonResult.isNullOrBlank()) return

        try {
            val jsonObject = org.json.JSONObject(jsonResult)
            val text = when {
                jsonObject.has("text") && jsonObject.getString("text").isNotBlank() -> jsonObject.getString("text")
                jsonObject.has("partial") && jsonObject.getString("partial").isNotBlank() -> jsonObject.getString("partial")
                else -> null
            }

            text?.let {
                updateText(it)
            }
        } catch (e: org.json.JSONException) {
            Log.e("VoskProcess", "Failed to parse Vosk JSON result: $jsonResult", e)
        } catch (e: Exception) {
            Log.e("VoskProcess", "Error processing Vosk result", e)
        }
    }
    // --- Bitiş: Vosk Dinleme Fonksiyonları ---


    // --- Google Dinleme Fonksiyonları ---
    private fun startGoogleListening(onResult: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Mikrofon izni verilmedi", Toast.LENGTH_SHORT).show()
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        releaseVoskResources()

        try {
            if (googleSpeechRecognizer == null) {
                Log.d("GoogleListen", "Creating Google SpeechRecognizer...")
                googleSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                setupGoogleSpeechRecognizer(onResult)
                Log.d("GoogleListen", "Google SpeechRecognizer created and listener set.")
            } else {
                Log.d("GoogleListen", "Google SpeechRecognizer already exists. Stopping previous listening.")
                googleSpeechRecognizer?.stopListening()
                googleSpeechRecognizer?.cancel()
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Google dinliyor...")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            Log.d("GoogleListen", "Starting Google listening with intent language: ${intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)}")
            googleSpeechRecognizer?.startListening(intent)
            updateText("Google başlatılıyor...")

        } catch (e: Exception) {
            Log.e("GoogleListen", "Error starting Google listening", e)
            Toast.makeText(this, "Google dinleme başlatılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
            releaseGoogleRecognizer()
        }
    }

    private fun setupGoogleSpeechRecognizer(onResult: (String) -> Unit) {
        googleSpeechRecognizer?.setRecognitionListener(object : GoogleRecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("GoogleCallback", "onReadyForSpeech")
                updateText("Google dinliyor...")
            }

            override fun onBeginningOfSpeech() {
                Log.d("GoogleCallback", "onBeginningOfSpeech")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray) {}

            override fun onEndOfSpeech() {
                Log.d("GoogleCallback", "onEndOfSpeech")
                updateText("Google işliyor...")
            }

            override fun onError(error: Int) {
                val errorMessage = getErrorText(error)
                Log.e("GoogleCallback", "onError: $error - $errorMessage")
                updateText("Google Hatası: $errorMessage")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    Log.d("GoogleCallback", "onResults: $text")
                    updateText(text)
                } else {
                    Log.d("GoogleCallback", "onResults: No matches found")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partialText = matches[0]
                    updateText(partialText)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d("GoogleCallback", "onEvent: $eventType")
            }
        })
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Ses kaydı hatası"
            SpeechRecognizer.ERROR_CLIENT -> "İstemci tarafı hatası (İnternet?)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Yetersiz izinler"
            SpeechRecognizer.ERROR_NETWORK -> "Ağ hatası"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Ağ zaman aşımı"
            SpeechRecognizer.ERROR_NO_MATCH -> "Anlaşılamadı"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Tanıyıcı meşgul"
            SpeechRecognizer.ERROR_SERVER -> "Sunucu hatası"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Konuşma algılanmadı"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Dil desteklenmiyor/mevcut değil"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Dil desteklenmiyor"
            14 -> "Ses yok veya çok kısa"
            else -> "Bilinmeyen Google hatası ($errorCode)"
        }
    }
    // --- Bitiş: Google Dinleme Fonksiyonları ---


    // --- Yardımcı Fonksiyonlar ---
    private fun updateText(text: String) {
        runOnUiThread {
            viewModel.setRecognizedText(text)
        }
    }
    // --- Bitiş: Yardımcı Fonksiyonlar ---


    // --- Preview ---
    @Preview(showBackground = true)
    @Composable
    fun DefaultPreview() {
        val previewViewModel = MainViewModel(Application()) // Preview için Application mock'u
        val previewNavController = rememberNavController()
        MaterialTheme {
            SpeechToTextApp(viewModel = previewViewModel, navController = previewNavController, activity = LocalContext.current as MainActivity)
        }
    }
    // --- Bitiş: Preview ---
}