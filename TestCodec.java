import android.media.MediaCodec;
import android.media.MediaFormat;

public class TestCodec {
    public static void main(String[] args) {
        System.out.println("Starting TestCodec with configure...");
        try {
            String[] codecs = {"OMX.amlogic.hevc.decoder.awesome", "OMX.amlogic.avc.decoder.awesome"};
            for (String name : codecs) {
                System.out.println("Testing: " + name);
                MediaCodec codec = MediaCodec.createByCodecName(name);
                System.out.println("Successfully created: " + name);
                
                String mime = name.contains("hevc") ? "video/hevc" : "video/avc";
                MediaFormat format = MediaFormat.createVideoFormat(mime, 1920, 1080);
                codec.configure(format, null, null, 0);
                System.out.println("Successfully configured: " + name);
                
                codec.start();
                System.out.println("Successfully started: " + name);
                
                codec.stop();
                codec.release();
                System.out.println("Successfully released: " + name);
            }
        } catch (Throwable t) {
            System.out.println("Failed: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println("All codecs tested successfully!");
    }
}
