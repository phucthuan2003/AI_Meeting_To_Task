package vn.aimtt.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.Test;
import vn.aimtt.llm.LlmHttpTransport;
import static org.assertj.core.api.Assertions.*;

class PcmAudioActivityTest {
    static byte[] wav(int rate, int channels, int seconds, IntToDoubleFunction sample) {
        int count = rate * channels * seconds;
        var bytes = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + count * 2);
        bytes.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1);
        bytes.putShort((short) channels).putInt(rate).putInt(rate * channels * 2);
        bytes.putShort((short) (channels * 2)).putShort((short) 16);
        bytes.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(count * 2);
        for (int i = 0; i < count; i++) bytes.putShort((short) Math.round(sample.applyAsDouble(i) * 32767));
        return bytes.array();
    }

    @Test void silentWavNeverCallsRecognizerEvenIfItWouldInventConfidentText() {
        var calls = new AtomicInteger();
        var provider = new HttpSttProvider(new AudioProperties("key", null, true, null, null), (r, l) -> {
            calls.incrementAndGet(); throw new AssertionError("Silent audio must not reach STT");
        }, new ObjectMapper());
        for (var audio : List.of(wav(16000, 1, 15, i -> 0),
                wav(48000, 1, 1, i -> 0.02),
                wav(16000, 1, 1, i -> i % 2 == 0 ? 0.0002 : -0.0002))) {
            assertThat(provider.transcribe(audio, "wav", "whisper-large-v3-turbo", () -> true)).isEmpty();
        }
        assertThat(calls.get()).isZero();
    }

    @Test void removesArbitraryWordsOverSilentStartAndKeepsActualSegmentTextAndTime() {
        byte[] audio = wav(16000, 1, 15, i -> i >= 5 * 16000 && i < 7 * 16000
                ? 0.01 * Math.sin(i * 2 * Math.PI * 220 / 16000) : 0);
        var provider = new HttpSttProvider(new AudioProperties("key", null, true, null, null), (r, l) ->
                new LlmHttpTransport.Response(200, """
                        {"duration":15,"segments":[
                          {"start":0,"end":3,"text":"Một câu bất kỳ không ai nói.","avg_logprob":-0.1},
                          {"start":5,"end":7,"text":"Ờ, Mai kiểm tra API nhé."},
                          {"start":10,"end":14,"text":"Một đoạn tự thêm ở cuối."}
                        ]}
                        """.getBytes(StandardCharsets.UTF_8)), new ObjectMapper());
        assertThat(provider.transcribe(audio, "wav", "whisper-large-v3-turbo", () -> true))
                .containsExactly(new SttProvider.Segment(5000, 7000, "Ờ, Mai kiểm tra API nhé."));
    }

    @Test void shortQuietSoundAndOneActiveStereoChannelArePreserved() {
        byte[] quiet = wav(48000, 1, 15, i -> i >= 14 * 48000 && i < 14 * 48000 + 2400
                ? 0.004 * Math.sin(i * 2 * Math.PI * 220 / 48000) : 0);
        var activity = PcmAudioActivity.fromWav(quiet, "wav").orElseThrow();
        assertThat(activity.hasSignal()).isTrue();
        assertThat(activity.hasSignal(0, 12)).isFalse();
        assertThat(activity.hasSignal(14, 14.05)).isTrue();
        byte[] stereo = wav(16000, 2, 1, i -> i % 2 == 0 ? 0 : 0.01 * Math.sin(i * 0.1));
        assertThat(PcmAudioActivity.fromWav(stereo, "wav").orElseThrow().hasSignal()).isTrue();
    }

    @Test void unsupportedOrCorruptInputIsLeftToTheDecoderRatherThanTreatedAsSilence() {
        assertThat(PcmAudioActivity.fromWav(new byte[]{1}, "wav")).isEmpty();
        assertThat(PcmAudioActivity.fromWav(new byte[]{1}, "mp3")).isEmpty();
        byte[] truncated = java.util.Arrays.copyOf(wav(16000, 1, 1, i -> 0), 100);
        assertThat(PcmAudioActivity.fromWav(truncated, "wav")).isEmpty();
    }
}
