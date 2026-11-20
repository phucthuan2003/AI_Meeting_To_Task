package vn.aimtt.capture;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/** Conservative energy gate for PCM WAVs, including extension recording chunks. */
final class PcmAudioActivity {
    private final int[] activeFrames;
    private final double frameSeconds;

    private PcmAudioActivity(byte[] pcm, AudioFormat format) {
        int channels = format.getChannels(), stride = format.getFrameSize();
        int frameSamples = Math.max(1, Math.round(format.getSampleRate() * 0.02f));
        int samples = pcm.length / stride;
        frameSeconds = frameSamples / (double) format.getSampleRate();
        activeFrames = new int[(samples + frameSamples - 1) / frameSamples + 1];
        for (int frame = 0; frame < activeFrames.length - 1; frame++) {
            int start = frame * frameSamples, end = Math.min(samples, start + frameSamples);
            boolean active = false;
            for (int channel = 0; channel < channels && !active; channel++) {
                double sum = 0, squares = 0;
                for (int sample = start; sample < end; sample++) {
                    int offset = sample * stride + channel * 2;
                    double value = (short) ((pcm[offset] & 255) | (pcm[offset + 1] << 8)) / 32768.0;
                    sum += value;
                    squares += value * value;
                }
                int count = end - start;
                // Remove DC offset and exclude only near-silence (-60 dBFS RMS).
                active = squares / count - Math.pow(sum / count, 2) > 0.001 * 0.001;
            }
            activeFrames[frame + 1] = activeFrames[frame] + (active ? 1 : 0);
        }
    }

    static Optional<PcmAudioActivity> fromWav(byte[] audio, String suffix) {
        if (!"wav".equals(suffix)) return Optional.empty();
        try (var input = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audio))) {
            var format = input.getFormat();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                    || format.getSampleSizeInBits() != 16 || format.isBigEndian()
                    || format.getChannels() < 1 || format.getChannels() > 8
                    || format.getFrameSize() != format.getChannels() * 2
                    || !Float.isFinite(format.getSampleRate()) || format.getSampleRate() < 8000
                    || format.getSampleRate() > 192000) return Optional.empty();
            byte[] pcm = input.readAllBytes();
            if (pcm.length == 0 || pcm.length % format.getFrameSize() != 0) return Optional.empty();
            if (input.getFrameLength() >= 0 && input.getFrameLength() != pcm.length / format.getFrameSize()) {
                return Optional.empty();
            }
            if (pcm.length / (double) format.getFrameSize() / format.getSampleRate() > 3600) return Optional.empty();
            return Optional.of(new PcmAudioActivity(pcm, format));
        } catch (IOException | UnsupportedAudioFileException | IllegalArgumentException error) {
            // Other formats and malformed files still go through the STT decoder's validation.
            return Optional.empty();
        }
    }

    boolean hasSignal() {
        return activeFrames[activeFrames.length - 1] > 0;
    }

    boolean hasSignal(double startSeconds, double endSeconds) {
        // Allow 200 ms of ASR timestamp drift without rewriting the timestamps.
        int start = (int) Math.max(0, Math.floor((startSeconds - 0.2) / frameSeconds));
        int end = (int) Math.min(activeFrames.length - 1, Math.ceil((endSeconds + 0.2) / frameSeconds));
        return start < end && activeFrames[end] > activeFrames[start];
    }
}
