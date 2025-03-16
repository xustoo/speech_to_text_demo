package com.example.speech_to_text


import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel

import com.example.speech_to_text.ui.theme.Speech_to_TextTheme

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext // LocalContext için
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.util.Locale


class MainActivity : ComponentActivity() { //AppCompatActivity yerine ComponentActivity

    private val REQUEST_RECORD_AUDIO_PERMISSION = 200
    var speechService: SpeechService? = null
    var model: Model? = null
    var speechRecognizer: SpeechRecognizer? = null //Google için

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Vosk için gerekli başlangıç ayarları
        LibVosk.setLogLevel(LogLevel.INFO)

        // İzin kontrolü ve model yükleme (izin verilmişse)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        } else {
            initModel {
                setContent {
                    SpeechToTextApp() // Ana Composable fonksiyonumuz
                }
            }
        }
        setContent {
            SpeechToTextApp() // İzin olmasa bile içeriği göster.
        }
    }


    private fun initModel(onModelReady: () -> Unit) {
        StorageService.unpack(this, "vosk-model-small-tr-0.3", "model",
            { loadedModel ->
                model = loadedModel
                onModelReady() //Model hazır olduğunda bildirimde bulun.
            },
            { exception ->
                Toast.makeText(this, "Model yüklenirken hata: ${exception.message}", Toast.LENGTH_LONG).show()
                Log.e("Vosk", "Model yükleme hatası", exception)
            })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initModel {
                setContent {
                    SpeechToTextApp()
                }
            }
        } else {
            Toast.makeText(this, "Mikrofon izni gerekiyor!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        speechRecognizer?.destroy() //Google için
        model?.close()
    }

    //Inner class olarak ViewModel'i tanımla
    class MainViewModel : androidx.lifecycle.ViewModel() {
        private val _recognizedText = mutableStateOf("") //MutableLiveData yerine mutableStateOf
        val recognizedText: State<String> = _recognizedText //State olarak dışarıya aç

        private val _isVoskSelected = mutableStateOf(true)
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
}


@Composable
fun SpeechToTextApp(viewModel: MainActivity.MainViewModel = viewModel()) { //ViewModel'i parametre olarak al
    val recognizedText by viewModel.recognizedText
    val isVoskSelected by viewModel.isVoskSelected
    val activity = LocalContext.current as MainActivity //Context'e erişim


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
                        activity.startVoskListening(viewModel::setRecognizedText) //Fonksiyon referansı
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
            Button(onClick = { viewModel.clearText() })
            {
                Text("Temizle")
            }
        }
    }
}

fun MainActivity.startVoskListening(onResult: (String) -> Unit) { //Fonksiyon parametre olarak
    if (model != null) {
        try {
            speechService?.stop() //Önce durdur
            speechService = null //Sıfırla
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService!!.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(result: String) {
                    processVoskResult(result, isFinal = false, onResult)
                }

                override fun onResult(result: String) {
                    processVoskResult(result, isFinal = true, onResult)
                }

                override fun onFinalResult(result: String) {}
                override fun onError(exception: Exception) {
                    Toast.makeText(this@startVoskListening,"Vosk Hatası: ${exception.message}",Toast.LENGTH_LONG).show()
                }
                override fun onTimeout() {
                    Toast.makeText(this@startVoskListening, "Vosk Zaman Aşımı", Toast.LENGTH_SHORT).show()
                }
            })
        } catch (e: IOException) {
            Toast.makeText(this@startVoskListening,"Vosk başlatılamadı ${e.message}",Toast.LENGTH_LONG).show()
        }
    }
    else{
        Toast.makeText(this, "Vosk modeli yüklenemedi", Toast.LENGTH_SHORT).show()
    }
}

fun MainActivity.processVoskResult(jsonResult: String, isFinal: Boolean, onResult: (String) -> Unit) {
    val result = try {
        if (isFinal) {
            org.json.JSONObject(jsonResult).getString("text")
        } else {
            org.json.JSONObject(jsonResult).getString("partial")
        }
    } catch (e: Exception) {
        ""
    }

    if (result.isNotBlank()) {
        if (isFinal) {
            onResult(result) //Callback fonksiyonunu çağır
        } else {
            onResult(result) //Callback
        }
    }
}


fun MainActivity.startGoogleListening(onResult: (String) -> Unit) { //Fonksiyon parametre
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {

        if(speechRecognizer == null) //Eğer daha önce oluşturulmadıysa oluştur
        {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            setupGoogleSpeechRecognizer(onResult) //onResult'u buraya da aktar.
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Bir şeyler söyleyin...")
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        speechRecognizer?.startListening(intent) //Google
    } else {
        Toast.makeText(this, "Mikrofon izni verilmedi.", Toast.LENGTH_SHORT).show()
    }
}

//Yeni fonksiyon: Google için RecognitionListener'ı kur. onResult parametresi
fun MainActivity.setupGoogleSpeechRecognizer(onResult: (String) -> Unit)
{
    speechRecognizer?.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Toast.makeText(this@setupGoogleSpeechRecognizer, "Dinlemeye hazır!", Toast.LENGTH_SHORT).show()
        }

        override fun onBeginningOfSpeech() {
            onResult("Dinleniyor...") //Callback ile bildir.
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            onResult("Dinleniyor...\nDinleme Bitti")
        }

        override fun onError(error: Int) {
            val errorMessage = getErrorText(error)
            Toast.makeText(this@setupGoogleSpeechRecognizer, "Hata: $errorMessage", Toast.LENGTH_LONG).show()
            Log.e("SpeechRecognizer", "Error: $errorMessage")
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val text = matches[0]
                onResult(text) //Callback ile bildir.
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val text = matches[0]
                onResult(text) //Callback ile bildir
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

}

//Yardımcı fonksiyon: Hata mesajlarını çevir
private fun MainActivity.getErrorText(errorCode: Int): String {
    return when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO -> "ASes kaydı hatası"
        SpeechRecognizer.ERROR_CLIENT -> "Aİstemci tarafı hatası"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "AYetersiz izinler"
        SpeechRecognizer.ERROR_NETWORK -> "AAğ hatası"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "AAğ zaman aşımı"
        SpeechRecognizer.ERROR_NO_MATCH -> "AEşleşme bulunamadı"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ATanıyıcı meşgul"
        SpeechRecognizer.ERROR_SERVER -> "ASunucu hatası"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "AKonuşma zaman aşımı"
        else -> "Bilinmeyen hata"
    }
}

@Preview
@Composable
fun PreviewSpeechToTextApp(){
    SpeechToTextApp() //viewModel parametresi almadan çağır.
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    SpeechToTextApp()
}