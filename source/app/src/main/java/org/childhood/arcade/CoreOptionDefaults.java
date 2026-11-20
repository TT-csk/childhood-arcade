package org.childhood.arcade;

/** Select complete legacy libretro option values, including multiword values. */
public final class CoreOptionDefaults {
    private CoreOptionDefaults() {}
    public static String select(String key, String spec) {
        int separator = spec.indexOf(';');
        String selected = spec.substring(separator + 1).trim().split("\\|", 2)[0];
        if ((key.equals("picodrive_input1") || key.equals("picodrive_input2"))
                && spec.contains("6 button pad")) selected = "6 button pad";
        if (key.toLowerCase(java.util.Locale.ROOT).contains("neogeo")
                && spec.contains("MVS")) selected = "MVS";
        // The pinned FCEUmm core's Low mode produces silent PCM in desktop QA.
        if (key.equals("fceumm_sndquality") && spec.contains("High")) selected = "High";
        return selected;
    }
}
