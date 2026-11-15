package kr.gakim.charge.data.pleos

import android.content.Context
import kr.gakim.charge.data.GatewaySet
import kr.gakim.charge.data.fake.FakeNaviGateway
import kr.gakim.charge.data.fake.FakeSpeechGateway
import kr.gakim.charge.data.fake.FakeSpeechOutputGateway
import kr.gakim.charge.data.fake.FakeVehicleGateway
import kr.gakim.charge.demo.DemoDataRepository

/** Pleos SDK 구현과 Fake 구현 중 하나를 만든다. */
object GatewayFactory {

    /** Manifest 에 선언한 Pleos 권한. 실행 중 요청이 필요한지는 에뮬레이터에서 확인해야 한다(미검증). */
    val PLEOS_PERMISSIONS = arrayOf(
        "pleos.car.permission.CAR_ENERGY",
        "pleos.car.permission.CAR_DRIVE",
        "pleos.car.permission.NAVI_ROUTE",
        "pleos.car.permission.NAVI_ROUTE_SEARCH",
        "pleos.car.permission.NAVI_CUSTOM_MAP",
        "pleos.car.permission.STT_SERVICE",
        "pleos.car.permission.TTS_SERVICE",
    )

    fun create(context: Context, useFakes: Boolean): GatewaySet =
        if (useFakes) {
            GatewaySet(
                vehicle = FakeVehicleGateway(),
                navi = FakeNaviGateway(destination = DemoDataRepository.SCHOOL),
                speech = FakeSpeechGateway(),
                speechOutput = FakeSpeechOutputGateway(),
                usingFakes = true,
            )
        } else {
            GatewaySet(
                vehicle = PleosVehicleGateway(context),
                navi = PleosNaviGateway(context),
                speech = PleosSpeechGateway(context),
                speechOutput = PleosSpeechOutputGateway(context),
                usingFakes = false,
            )
        }
}
