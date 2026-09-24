    // ===================== ЭКСТРЕННЫЕ СЛУЖБЫ =====================
    fun callEmergency(context: Context, number: String, name: String): String {
        return try {
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:" + number)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Звоню " + name + "."
        } catch (e: SecurityException) {
            try {
                val intent = Intent(Intent.ACTION_DIAL)
                intent.data = Uri.parse("tel:" + number)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Открыла набор номера для " + name + "."
            } catch (e2: Exception) {
                "Не могу позвонить в " + name + "."
            }
        } catch (e: Exception) {
            "Не могу позвонить: " + (e.message ?: "")
        }
    }

    fun callAmbulance(context: Context): String =
        callEmergency(context, "103", "скорую помощь")

    fun callPolice(context: Context): String =
        callEmergency(context, "102", "полицию")

    fun callFire(context: Context): String =
        callEmergency(context, "101", "пожарную")

    fun callEmergency112(context: Context): String =
        callEmergency(context, "112", "службу спасения")
}
