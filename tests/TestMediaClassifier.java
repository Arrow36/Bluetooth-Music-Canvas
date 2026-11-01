package tests;

import com.bluetoothmusiccanvas.MediaClassifier;
import com.bluetoothmusiccanvas.MetadataNormalizer;

public class TestMediaClassifier {

    private static void assertTrue(String msg, boolean condition) {
        if (!condition) {
            throw new AssertionError("FAILED: " + msg);
        }
        System.out.println("  [PASS] " + msg);
    }

    public static void main(String[] args) {
        System.out.println(">>> Running MediaClassifier Unit Tests <<<");

        // 1. Ambient & System Audio
        MediaClassifier.ClassificationResult r1 = MediaClassifier.classify(
                "蓝牙媒体音频", "未知歌手", "", 0, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Ambient audio recognized as VIDEO", r1.isVideo() && "AMBIENT_AUDIO".equals(r1.reasonCode));

        // 2. Car dynamic lyric stream confirmed
        MediaClassifier.ClassificationResult r2 = MediaClassifier.classify(
                "某些奇特的长标题视频名", "UP主", "", 0, MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED);
        assertTrue("Car dynamic lyric stream confirmed as MUSIC", r2.isMusic() && r2.evidenceLevel == MediaClassifier.EvidenceLevel.EXPLICIT);

        // 3. Explicit video platform in artist
        MediaClassifier.ClassificationResult r3 = MediaClassifier.classify(
                "每日精彩日常", "Bilibili-官方UP主", "", 120000, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Video platform artist recognized as VIDEO", r3.isVideo() && r3.evidenceLevel == MediaClassifier.EvidenceLevel.EXPLICIT);

        // 4. Strong album protection (even if title is long or unusual)
        MediaClassifier.ClassificationResult r4 = MediaClassifier.classify(
                "如何看待那些说再见的人 - 现场纯享版", "某歌手", "某正规数字专辑", 240000, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Legitimate album protects track as MUSIC", r4.isMusic() && "HAS_VALID_ALBUM".equals(r4.reasonCode));

        // 5. Classical music / instrumental protection
        MediaClassifier.ClassificationResult r5 = MediaClassifier.classify(
                "Beethoven: Piano Sonata No. 14 in C-sharp minor, Op. 27, No. 2 'Moonlight'",
                "Various Artists", "", 900000, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Classical sonata recognized as MUSIC", r5.isMusic() && "CLASSICAL_COMPOSITION".equals(r5.reasonCode));

        // 6. Video episode number pattern
        MediaClassifier.ClassificationResult r6 = MediaClassifier.classify(
                "进击的巨人 最终季 第12集", "WIT STUDIO", "", 1400000, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Episode number recognized as VIDEO", r6.isVideo() && "EPISODE_NUMBER".equals(r6.reasonCode));

        // 7. Video brackets
        MediaClassifier.ClassificationResult r7 = MediaClassifier.classify(
                "【4K60帧】沉浸式体验开箱测评", "", "", 600000, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Video bracket recognized as VIDEO", r7.isVideo() && "VIDEO_BRACKET_TAG".equals(r7.reasonCode));

        // 8. Standard music track
        MediaClassifier.ClassificationResult r8 = MediaClassifier.classify(
                "晴天", "周杰伦", "叶惠美", 269000, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Standard music track recognized as MUSIC", r8.isMusic());

        System.out.println(">>> All MediaClassifier Tests Passed! <<<\n");
    }
}
