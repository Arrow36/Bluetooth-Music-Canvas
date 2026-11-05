package com.bluetoothmusiccanvas;

import java.util.Locale;

/**
 * Sole authoritative classifier for determining whether a media track
 * should be rendered as a music track (vinyl, cover art, lyrics) or video/ambient spectrum.
 */
public class MediaClassifier {

    public enum ContentKind {
        MUSIC,
        VIDEO,
        UNKNOWN
    }

    public enum EvidenceLevel {
        EXPLICIT,   // Confirmed via car lyric stream or explicit system stream
        MATCHED,    // Confirmed via valid album metadata or classical composition
        HEURISTIC,  // Inferred via keyword rules or episode pattern
        NONE        // No reliable evidence
    }

    public static class ClassificationResult {
        public final ContentKind kind;
        public final EvidenceLevel evidenceLevel;
        public final String reasonCode;

        public ClassificationResult(ContentKind kind, EvidenceLevel level, String reason) {
            this.kind = kind;
            this.evidenceLevel = level;
            this.reasonCode = reason != null ? reason : "";
        }

        public boolean isVideo() {
            return kind == ContentKind.VIDEO;
        }

        public boolean isMusic() {
            return kind == ContentKind.MUSIC;
        }

        @Override
        public String toString() {
            return kind + " (" + evidenceLevel + ": " + reasonCode + ")";
        }
    }

    private static final String VIDEO_BRACKET_REGEX =
            ".*[【「\\[](.*(4k|1080p|60帧|解说|实况|测评|评测|开箱|自制|熟肉|生肉|合集|日常|vlog|切片|录播|二创|鬼畜|短剧|纯享|直拍|饭拍|花絮|挑战|盘点).*|\\s*\\d+\\s*)[】」\\]].*";

    private static final String EPISODE_OR_SEAT_REGEX =
            ".*第\\s*\\d+\\s*[集期话回季篇排场站].*";

    private static final String[] VIDEO_KEYWORDS = {
        "vlog", "解说", "实况", "测评", "评测", "开箱", "预告片", "官方预告", "直播回放",
        "切片", "鬼畜", "短剧", "通关攻略", "游戏实况", "录播", "二创", "教程", "盘点",
        "巡演前", "前准备", "花絮", "幕后", "直拍", "饭拍", "完整版", "全场回放", "名场面",
        "斥巨资", "挑战", "探店", "打卡", "可刷", "刷机", "触屏音箱",
        "reaction", "gameplay", "walkthrough", "speedrun", "trailer", "teaser", "livestream"
    };

    private static boolean containsVideoKeyword(String lowerText) {
        if (lowerText == null || lowerText.isEmpty()) return false;
        for (String kw : VIDEO_KEYWORDS) {
            if (lowerText.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    public static ClassificationResult classify(String rawTitle, String rawArtist, String rawAlbum,
                                                long duration, MetadataNormalizer.MetadataFormat format) {
        if (rawTitle == null || rawTitle.trim().isEmpty() || "Not Provided".equalsIgnoreCase(rawTitle.trim())) {
            return new ClassificationResult(ContentKind.UNKNOWN, EvidenceLevel.NONE, "EMPTY_METADATA");
        }

        String t = rawTitle.trim();
        String a = rawArtist != null ? rawArtist.trim() : "";
        String alb = rawAlbum != null ? rawAlbum.trim() : "";

        // 1. Explicit ambient audio or system audio stream
        if (t.equals("蓝牙媒体音频") || a.contains("视频 / 外部音频") || a.contains("视频 / 媒体声音")) {
            return new ClassificationResult(ContentKind.VIDEO, EvidenceLevel.HEURISTIC, "AMBIENT_AUDIO");
        }

        // 2. Explicit car Bluetooth dynamic lyric stream (firmly proves active music track)
        if (format == MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED) {
            return new ClassificationResult(ContentKind.MUSIC, EvidenceLevel.EXPLICIT, "CAR_LYRIC_STREAM");
        }

        String lowerTitle = t.toLowerCase(Locale.ROOT);
        String lowerArtist = a.toLowerCase(Locale.ROOT);
        String lowerAlbum = alb.toLowerCase(Locale.ROOT);

        // 3. Explicit video platform / creator signatures in artist field
        if (lowerArtist.contains("bilibili") || lowerArtist.contains("b站") || lowerArtist.contains("哔哩哔哩")
                || lowerArtist.contains("youtube") || lowerArtist.contains("douyin") || lowerArtist.contains("抖音")
                || lowerArtist.contains("kuaishou") || lowerArtist.contains("快手") || lowerArtist.contains("tiktok")
                || lowerArtist.contains("tencent video") || lowerArtist.contains("iqiyi") || lowerArtist.contains("youku")
                || lowerArtist.contains("up主") || lowerArtist.contains("官方频道")
                || lowerArtist.endsWith("diy") || lowerArtist.contains("工作室") || lowerArtist.contains("实验室")) {
            return new ClassificationResult(ContentKind.VIDEO, EvidenceLevel.EXPLICIT, "VIDEO_PLATFORM_ARTIST");
        }

        // 4. Strong protection: valid independent album name confirms music
        // Note: Bilibili and video apps often copy the video title into the album field (lowerAlbum.equals(lowerTitle))
        // or put a video folder name (like "开箱评测", "日常合集") into the album field.
        boolean albumIsDistinctFromTitle = !alb.isEmpty() && !lowerAlbum.equals(lowerTitle);
        boolean albumHasVideoMarkers = lowerAlbum.contains("bilibili") || lowerAlbum.contains("youtube")
                || lowerAlbum.contains("douyin") || lowerAlbum.contains("tiktok") || lowerAlbum.contains("快手")
                || lowerAlbum.contains("小红书") || containsVideoKeyword(lowerAlbum)
                || alb.matches(EPISODE_OR_SEAT_REGEX) || lowerAlbum.matches(VIDEO_BRACKET_REGEX);

        if (albumIsDistinctFromTitle && !albumHasVideoMarkers) {
            if (!t.matches(EPISODE_OR_SEAT_REGEX) && !lowerTitle.matches(VIDEO_BRACKET_REGEX)) {
                return new ClassificationResult(ContentKind.MUSIC, EvidenceLevel.MATCHED, "HAS_VALID_ALBUM");
            }
        }

        // 5. Strong protection: classical music & instrumental terms (even if title is very long)
        if (lowerTitle.contains("sonata") || lowerTitle.contains("奏鸣曲")
                || lowerTitle.contains("symphony") || lowerTitle.contains("交响")
                || lowerTitle.contains("concerto") || lowerTitle.contains("协奏")
                || lowerTitle.contains("nocturne") || lowerTitle.contains("夜曲")
                || lowerTitle.contains("prelude") || lowerTitle.contains("前奏曲")
                || lowerTitle.contains("etude") || lowerTitle.contains("练习曲")
                || lowerTitle.contains("rhapsody") || lowerTitle.contains("狂想曲")
                || lowerTitle.contains("suite") || lowerTitle.contains("组曲")
                || lowerTitle.contains("waltz") || lowerTitle.contains("圆舞曲")
                || lowerTitle.contains("serenade") || lowerTitle.contains("小夜曲")
                || lowerTitle.contains("op.") || lowerTitle.contains("bwv")
                || lowerTitle.contains("k.") || lowerTitle.contains("hob.")
                || lowerTitle.contains("rv ") || lowerTitle.contains("d.")
                || lowerTitle.contains("乐章") || lowerTitle.contains("andante")
                || lowerTitle.contains("allegro") || lowerTitle.contains("adagio")) {
            return new ClassificationResult(ContentKind.MUSIC, EvidenceLevel.MATCHED, "CLASSICAL_COMPOSITION");
        }

        // 6. Video episode / seat / venue patterns (第2集, 第10期, 第5话, 第1排, etc.)
        if (t.matches(EPISODE_OR_SEAT_REGEX)) {
            return new ClassificationResult(ContentKind.VIDEO, EvidenceLevel.HEURISTIC, "EPISODE_NUMBER");
        }

        // 7. Video brackets (【4K】, 【解说】, 「开箱评测」, 【3】, etc.)
        if (lowerTitle.matches(VIDEO_BRACKET_REGEX)) {
            return new ClassificationResult(ContentKind.VIDEO, EvidenceLevel.HEURISTIC, "VIDEO_BRACKET_TAG");
        }

        // 8. Video keywords in title or album
        for (String kw : VIDEO_KEYWORDS) {
            if (lowerTitle.contains(kw) || lowerAlbum.contains(kw)) {
                return new ClassificationResult(ContentKind.VIDEO, EvidenceLevel.HEURISTIC, "KEYWORD_" + kw.toUpperCase(Locale.ROOT));
            }
        }

        // 9. Date stamp or pipe-separated live clip / vlog titles (e.g. "2026.10.20... | 长沙场")
        if (t.matches(".*20\\d{2}[.\\-/年]\\d{1,2}[.\\-/月]\\d{1,2}.*")
                || t.matches(".*\\|\\s*[^|]*(场|站|排|回放|直拍|饭拍|集|期).*")) {
            return new ClassificationResult(ContentKind.VIDEO, EvidenceLevel.HEURISTIC, "LIVE_CLIP_PATTERN");
        }

        // 10. Video question / clickbait exclamation / quoted conversational sentence patterns
        if (t.contains("如何看待") || t.contains("为什么") || t.contains("一口气看完") || t.contains("大结局")) {
            if (t.contains("？") || t.contains("?") || t.contains("！") || t.contains("!")) {
                return new ClassificationResult(ContentKind.VIDEO, EvidenceLevel.HEURISTIC, "QUESTION_PATTERNS");
            }
        }
        if ((t.contains("！") || t.contains("!")) && (t.contains("…") || t.contains("..."))) {
            return new ClassificationResult(ContentKind.VIDEO, EvidenceLevel.HEURISTIC, "CLICKBAIT_PUNCTUATION");
        }
        if (t.length() >= 10 && (t.startsWith("“") || t.startsWith("\"") || t.startsWith("「"))
                && (t.endsWith("”") || t.endsWith("\"") || t.endsWith("」"))
                && (t.contains("，") || t.contains("！") || t.contains("？"))) {
            return new ClassificationResult(ContentKind.VIDEO, EvidenceLevel.HEURISTIC, "QUOTED_SENTENCE_TITLE");
        }

        // Default: standard track is music
        return new ClassificationResult(ContentKind.MUSIC, EvidenceLevel.HEURISTIC, "STANDARD_TRACK");
    }
}
