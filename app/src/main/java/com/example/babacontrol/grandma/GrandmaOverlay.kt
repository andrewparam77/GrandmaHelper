    private fun handleQuestion(text: String) {
        if (isLoading) return
        if (text.isBlank()) return

        val provider = Prefs.getProvider(this)
        val config = buildConfig(provider)

        isLoading = true
        statusText?.text = "Думаю..."
        micButton?.text = "Спросить"

        scope.launch {
            try {
                val facts = memory.loadFacts()
                val history = memory.load().takeLast(20)
                val name = Prefs.getGrandmaName(this@GrandmaOverlay)
                val screenText = if (looksLikeHowToQuestion(text) && GrandmaScreenReader.isRunning()) {
                    GrandmaScreenReader.readCurrentScreen()
                } else ""
                val systemPrompt = buildOverlayPrompt(name, facts, screenText)
                val answer = GrandmaAiClient.ask(
                    this@GrandmaOverlay, config, systemPrompt, history, text
                )

                answerText?.text = answer
                tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "ov_" + System.currentTimeMillis())

                val updated = memory.load().toMutableList()
                updated.add(GrandmaMsg(text, true))
                updated.add(GrandmaMsg(answer, false))
                memory.save(updated)
            } catch (e: Exception) {
                statusText?.text = "Ошибка: " + (e.message ?: "")
            } finally {
                isLoading = false
                val st = statusText?.text ?: ""
                if (!st.startsWith("Ошибка")) statusText?.text = ""
            }
        }
    }
