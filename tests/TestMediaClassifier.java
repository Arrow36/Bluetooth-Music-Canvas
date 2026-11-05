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

        // 9. Real Bilibili videos where album == title or album is a video category
        MediaClassifier.ClassificationResult r9 = MediaClassifier.classify(
                "蔡珂宜 巡演前准备 Chloe Chua", "激素不给你", "蔡珂宜 巡演前准备 Chloe Chua", 180000, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Bilibili tour prep video (album==title) recognized as VIDEO", r9.isVideo());

        MediaClassifier.ClassificationResult r10 = MediaClassifier.classify(
                "蔡珂宜 2026.10.20庞塞<小星星>完整版 | 长沙场第1排", "HzCat赫兹猫", "蔡珂宜 2026.10.20庞塞<小星星>完整版 | 长沙场第1排", 240000, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Bilibili concert clip with date/seat (album==title) recognized as VIDEO", r10.isVideo());

        MediaClassifier.ClassificationResult r11 = MediaClassifier.classify(
                "「开箱评测」199的RK3566触屏音箱 4+128超大容量 完美Project Treblek可刷GSI 国美云", "郑羊羊", "开箱评测", 600000, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Bilibili unboxing review with folder album recognized as VIDEO", r11.isVideo());

        MediaClassifier.ClassificationResult r12 = MediaClassifier.classify(
                "斥巨资！让07后小朋友给我买衣服！结果…！【3】", "宝剑嫂", "斥巨资！让07后小朋友给我买衣服！结果…！【3】", 480000, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Bilibili numbered vlog with 【3】 recognized as VIDEO", r12.isVideo());

        MediaClassifier.ClassificationResult r13 = MediaClassifier.classify(
                "“我不是叛逆，这只是我的审美态度”", "拉宏桑", "“我不是叛逆，这只是我的审美态度”", 180000, MetadataNormalizer.MetadataFormat.UNKNOWN);
        assertTrue("Bilibili quoted sentence title recognized as VIDEO", r13.isVideo());

        System.out.println(">>> All MediaClassifier Tests Passed! <<<\n");
    }
}
