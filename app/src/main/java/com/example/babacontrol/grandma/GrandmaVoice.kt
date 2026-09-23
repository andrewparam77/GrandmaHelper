package com.example.babacontrol.grandma

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class GrandmaVoice(private val context: Context) {
    var onListeningChanged: (Boolean) -> Unit = {}
    var onResult: (String) -> Unit = {}
    var onError: (String) -> Unit = {}

    private var recognizer: SpeechRecognizer? = null

    private fun ensure(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { onListeningChanged(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { onListeningChanged(false) }
            override fun onError(error: Int) {
                onListeningChanged(false)
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышала, повторите"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Тишина, попробуйте ещё раз"
                    SpeechRecognizer.ERROR_AUDIO -> "Ошибка микрофона"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет интернета"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Занято, попробуйте ещё раз"
                    else -> "Ошибка распознавания ($error)"
                }
                onError(msg)
            }
            override fun onResults(results: Bundle?) {
                onListeningChanged(false)
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()
                if (!text.isNullOrBlank()) onResult(text.trim()) else onError("Не расслышала")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = r
        return r
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Установите Google — нужен сервис распознавания речи")
            return
        }
        val r = ensure()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            r.startListening(intent)
            onListeningChanged(true)
        } catch (e: Exception) {
            onError("Не удалось запустить: ${e.message}")
        }
    }

    fun stop() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        onListeningChanged(false)
    }

    fun destroy() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }
}