package com.example.speech_to_text

import android.Manifest
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
import androidx.lifecycle.viewmodel.compose.viewModel
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.util.Locale
import android.speech.RecognitionListener as GoogleRecognitionListener // Çakışmayı önlemek için
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels


class MainActivity : ComponentActivity() {

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var speechRecognizer: SpeechRecognizer? = null // Google için

    private val viewModel: MainViewModel by viewModels() // Dikkat: by viewModels()


    // İzin İsteği için ActivityResultLauncher
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                initModel() // İzin verildiyse modeli yükle
            } else {
                Toast.makeText(this, "Mikrofon izni gerekiyor!", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LibVosk.setLogLevel(LogLevel.INFO)
        checkRecordAudioPermission() //İzin isteme

        setContent {
            val viewModel: MainViewModel = viewModel() // ViewModel'i burada oluştur
            SpeechToTextApp(viewModel)
        }
    }

    private fun checkRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // İzin istenmediyse veya reddedildiyse, izin iste
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            // İzin zaten verildiyse, modeli yükle
            initModel()
        }
    }


    private fun initModel() {
        StorageService.unpack(this, "vosk-model-small-en-0.15", "model", //Model ismi
            { loadedModel ->
                model = loadedModel
                initVoskRecognizer()
            },
            { exception ->
                Toast.makeText(this, "Model yüklenirken hata: ${exception.message}", Toast.LENGTH_LONG).show()
                Log.e("Vosk", "Model yükleme hatası", exception)
            }
        )
    }

    private fun initVoskRecognizer() {
        try {
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
        } catch (e: IOException) {
            Toast.makeText(this, "Vosk başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        speechRecognizer?.destroy()
        model?.close()
    }

    class MainViewModel : androidx.lifecycle.ViewModel() {
        private val _recognizedText = mutableStateOf("")
        val recognizedText: State<String> = _recognizedText

        private val _isVoskSelected = mutableStateOf(true) // True: Vosk, False: Google
        val isVoskSelected: State<Boolean> = _isVoskSelected

        fun setRecognizedText(text: String) {
            _recognizedText.value = text
        }

        fun toggleVoskSelection() {
            _isVoskSelected.value = !_isVoskSelected.value
        }

        fun clearText() {
            _recognizedText.value = ""
        }
    }

    @Composable
    fun SpeechToTextApp(viewModel: MainViewModel) {
        val recognizedText by viewModel.recognizedText
        val isVoskSelected by viewModel.isVoskSelected
        val activity = LocalContext.current as MainActivity

        MaterialTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = recognizedText,
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp)
                )

                Button(
                    onClick = {
                        if (isVoskSelected) {
                            activity.startVoskListening(viewModel::setRecognizedText)
                        } else {
                            activity.startGoogleListening(viewModel::setRecognizedText)
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text("Konuşmayı Başlat")
                }

                Button(onClick = { viewModel.toggleVoskSelection() }) {
                    Text(if (isVoskSelected) "Vosk Kullanılıyor" else "Google Kullanılıyor")
                }
                Button(onClick = { viewModel.clearText() }) {
                    Text("Temizle")
                }
            }
        }
    }


    private fun startVoskListening(onResult: (String) -> Unit) {
        if (model == null) {
            Toast.makeText(this, "Vosk modeli yüklenemedi", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            speechService?.stop() // Önce durdur
            speechService = null // Sıfırla, yeni bir tane oluştur.
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(object :
                org.vosk.android.RecognitionListener { // Doğru import ve kullanım
                override fun onPartialResult(partialResult: String) {
                    processVoskResult(partialResult, isFinal = false, onResult)
                }

                override fun onResult(result: String) {
                    processVoskResult(result, isFinal = true, onResult)
                    stopListening()  // Vosk'u durdur
                    startListening() // ve tekrar başlat (sürekli dinleme için)
                }

                override fun onFinalResult(hypothesis: String?) {
                    TODO("Not yet implemented")
                }

                override fun onError(error: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Vosk Hatası: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onTimeout() {
                    Toast.makeText(this@MainActivity, "Vosk Zaman Aşımı", Toast.LENGTH_SHORT)
                        .show()
                }
            })
        } catch (e: IOException) {
            Toast.makeText(
                this,
                "Vosk başlatılırken hata oluştu: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    private fun processVoskResult(jsonResult: String, isFinal: Boolean, onResult: (String) -> Unit) {
        val result = try {
            if(isFinal) {
                org.json.JSONObject(jsonResult).getString("text")
            }
            else{
                org.json.JSONObject(jsonResult).getString("partial")
            }
        } catch (e: Exception) {
            ""
        }
        if(result.isNotBlank()){
            onResult(result)
        }

    }


    private fun startGoogleListening(onResult: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                setupGoogleSpeechRecognizer(onResult)
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Bir şeyler söyleyin...")
            intent.putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
            ) //Kısmi sonuçları almak için

            speechRecognizer?.startListening(intent)
        } else {
            Toast.makeText(this, "Mikrofon izni verilmedi", Toast.LENGTH_SHORT).show()
        }

    }


    private fun setupGoogleSpeechRecognizer(onResult: (String) -> Unit) {
        speechRecognizer?.setRecognitionListener(object :
            GoogleRecognitionListener {  // Burada çakışmayı çöz
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(
                    this@MainActivity,
                    "Dinlemeye hazır!",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onBeginningOfSpeech() {
                onResult("Dinleniyor...")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray) {}

            override fun onEndOfSpeech() {
                onResult("Dinleniyor... (Bitti)") // veya boş bırakabilirsiniz
            }

            override fun onError(error: Int) {
                val errorMessage = getErrorText(error)
                Toast.makeText(
                    this@MainActivity,
                    "Hata: $errorMessage",
                    Toast.LENGTH_LONG
                ).show()
                Log.e("SpeechRecognizer", "Error: $errorMessage")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches =
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                }
            }

            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
    }


    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Ses kaydı hatası"
            SpeechRecognizer.ERROR_CLIENT -> "İstemci tarafı hatası"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Yetersiz izinler"
            SpeechRecognizer.ERROR_NETWORK -> "Ağ hatası"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Ağ zaman aşımı"
            SpeechRecognizer.ERROR_NO_MATCH -> "Eşleşme bulunamadı"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Tanıyıcı meşgul"
            SpeechRecognizer.ERROR_SERVER -> "Sunucu hatası"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Konuşma zaman aşımı"
            else -> "Bilinmeyen hata"
        }
    }

    private fun stopListening(){ //Vosk için durdurma metodu
        speechService?.stop()
    }
    private fun startListening(){ //Vosk için başlatma metodu.
        speechService?.startListening(object : org.vosk.android.RecognitionListener { // Doğru import ve kullanım
            override fun onPartialResult(partialResult: String) {
                processVoskResult(partialResult, isFinal = false, ::updateText)
            }

            override fun onResult(result: String) {
                processVoskResult(result, isFinal = true, ::updateText)
                stopListening()  // Vosk'u durdur
                startListening() // ve tekrar başlat (sürekli dinleme için)
            }

            override fun onFinalResult(hypothesis: String?) {
                TODO("Not yet implemented")
            }

            override fun onError(error: Exception) {
                Toast.makeText(this@MainActivity, "Vosk Hatası: ${error.message}", Toast.LENGTH_LONG)
                    .show()
            }

            override fun onTimeout() {
                Toast.makeText(this@MainActivity, "Vosk Zaman Aşımı", Toast.LENGTH_SHORT).show()
            }
        })
    }
    private  fun updateText(text: String){ //Yardımcı bir method
        runOnUiThread{
            //val viewModel: MainViewModel = viewModel()
            viewModel.setRecognizedText(text)
        }
    }


    @Preview(showBackground = true)
    @Composable
    fun DefaultPreview() {
        MaterialTheme { // Use default MaterialTheme
            SpeechToTextApp(viewModel = MainViewModel())
        }
    }
}