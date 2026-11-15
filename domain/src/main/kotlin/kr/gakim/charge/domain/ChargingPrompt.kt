package kr.gakim.charge.domain

/** 사용자가 고른 충전 의향. */
enum class ChargePreference(val label: String) {
    AUTO("자동"),
    WANT("원함"),
    DECLINE("원하지 않음"),
}

enum class ChargingPrompt(val label: String) {
    COMPARE_CHARGING("충전 비교"),
    DEFAULT_ROUTE("기본 경로"),
    ASK_USER("사용자 선택"),
}

/**
 * 원함/원하지 않음은 그대로 따른다. 자동이면 도착 잔량이 기준 이하일 때만 충전을 비교한다.
 * 값이 없거나 0~100 밖이면 추측하지 않고 사용자에게 묻는다. 0%는 유효한 값이다.
 */
fun chargingPrompt(choice: ChargePreference, arrivalSoc: Int?, userThreshold: Int?): ChargingPrompt {
    if (choice == ChargePreference.WANT) return ChargingPrompt.COMPARE_CHARGING
    if (choice == ChargePreference.DECLINE) return ChargingPrompt.DEFAULT_ROUTE
    if (arrivalSoc == null || userThreshold == null ||
        arrivalSoc !in 0..100 || userThreshold !in 0..100
    ) {
        return ChargingPrompt.ASK_USER
    }
    return if (arrivalSoc <= userThreshold) ChargingPrompt.COMPARE_CHARGING else ChargingPrompt.DEFAULT_ROUTE
}
