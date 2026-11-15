plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
    // 실행한 테스트 개수를 콘솔에 남긴다(README·보고에 그대로 인용).
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit
        override fun beforeTest(testDescriptor: TestDescriptor) = Unit
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) = Unit
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                // 콘솔 코드페이지와 무관하게 읽히도록 ASCII 로 출력한다.
                println(
                    "[:domain:test] total=${result.testCount} passed=${result.successfulTestCount} " +
                        "failed=${result.failedTestCount} skipped=${result.skippedTestCount}"
                )
            }
        }
    })
}
