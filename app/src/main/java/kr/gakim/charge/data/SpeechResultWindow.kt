package kr.gakim.charge.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** End-of-speech may precede the final transcript. Keep the result channel alive for that callback. */
class SpeechResultWindow(private val scope: CoroutineScope, private val emit: (SpeechEvent)->Unit,
                         private val finish: ()->Unit, private val graceMs: Long = 2500) {
    private var terminal=false
    private var grace: Job?=null
    @Synchronized fun result(text:String, completed:Boolean) {
        if(terminal) return
        if(completed) {
            terminal=true;grace?.cancel();emit(SpeechEvent.Final(text));finish()
        } else emit(SpeechEvent.Partial(text))
    }
    @Synchronized fun ended() {
        if(terminal || grace!=null) return
        grace=scope.launch { delay(graceMs);error("발화는 끝났지만 확정 인식 결과를 받지 못했습니다") }
    }
    @Synchronized fun error(reason:String) {
        if(terminal) return
        terminal=true;grace?.cancel();emit(SpeechEvent.Error(reason));finish()
    }
    @Synchronized fun cancel() { terminal=true;grace?.cancel() }
}
