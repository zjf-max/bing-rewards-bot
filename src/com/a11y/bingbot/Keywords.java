package com.a11y.bingbot;

import java.util.Random;

/** 生成不重复的搜索词——重复查询不计分，这是实测结论。 */
public final class Keywords {

    private static final String[] TOPICS = {
            "sourdough starter", "pour over coffee", "mechanical keyboard", "noise cancelling headphones",
            "electric scooter", "solar panel", "drone battery", "mirrorless camera",
            "running shoes", "trail running", "home office desk", "standing desk",
            "air purifier", "water filter", "camping stove", "sleep quality",
            "intermittent fasting", "strength training", "swimming technique", "road bike",
            "carbon fiber frame", "tide table", "permaculture garden", "houseplant care",
            "film simulation", "vintage lens", "travel insurance", "budget hotel tokyo",
            "london walking route", "rail pass", "learn spanish", "jazz piano",
            "docker compose", "rust ownership", "python asyncio", "sqlite index",
            "raspberry pi", "home assistant", "wifi mesh", "vpn protocol",
            "lightning cable", "power bank", "usb hub", "thermal paste",
            "pc build", "quiet fan", "monitor calibration", "color grading",
            "bread baking", "knife sharpening", "cast iron pan", "smoker recipe"
    };

    private static final String[] QUALIFIERS = {
            "buying guide", "common mistakes", "setup", "comparison",
            "best practices", "beginner tips", "maintenance", "checklist",
            "vs alternatives", "review notes", "explained", "budget options",
            "advanced guide", "quick start", "field test", "long term"
    };

    private static final Random RND = new Random();
    private static long counter = System.currentTimeMillis() % 9973L;

    private Keywords() {}

    public static synchronized String next() {
        // 用时间计数驱动，保证即使不落盘也不会快速重复
        counter++;
        int ti = (int) ((counter * 31 + System.nanoTime() / 1000003L) % TOPICS.length);
        if (ti < 0) {
            ti = -ti;
        }
        int qi = (int) ((counter * 17 + System.nanoTime() / 1000033L) % QUALIFIERS.length);
        if (qi < 0) {
            qi = -qi;
        }
        String kw = TOPICS[ti] + " " + QUALIFIERS[qi];
        if (RND.nextInt(3) == 0) {
            kw = kw + " " + (1900 + RND.nextInt(120));
        }
        return kw;
    }
}
