package com.quizgate.spike.monitor

data class ParentExtension(val requestId: String = "", val stage: Stage = Stage.SENDING, val packageName: String = "") {
    enum class Stage { SENDING, PENDING, GRANTED, REJECTED, CLOSED, FAILED }
}
