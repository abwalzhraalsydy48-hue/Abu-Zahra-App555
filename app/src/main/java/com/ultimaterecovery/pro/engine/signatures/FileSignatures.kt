package com.ultimaterecovery.pro.engine.signatures

import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory

/**
 * قاعدة بيانات شاملة لتوقيعات الملفات (File Signatures / Magic Numbers)
 *
 * تحتوي هذه الفئة على جميع توقيعات الملفات المعروفة المستخدمة لتحديد
 * نوع الملف من خلال البايتات الأولى (Magic Bytes). كل توقيع يحتوي على:
 * - الاسم التعريفي
 * - قائمة الامتدادات المحتملة
 * - البايتات السحرية بالنظام الست عشري
 * - الإزاحة (Offset) في الملف حيث يبدأ التوقيع
 * - دالة مطابقة للتحقق من تطابق البايتات
 *
 * تستخدم هذه القاعدة بيانات في عملية المسح الضوئي لاستعادة الملفات
 * المحذوفة عبر تقنية File Carving.
 */
object FileSignatures {

    /**
     * يمثل توقيع ملف واحد مع جميع البيانات اللازمة للتعرف عليه
     *
     * @param name الاسم التعريفي للتوقيع (مثال: "JPEG")
     * @param extensions قائمة الامتدادات المرتبطة بهذا النوع
     * @param hexPattern النمط الست عشري كبايتات خام
     * @param offset الإزاحة في الملف حيث يبدأ التوقيع (عادةً 0)
     * @param category تصنيف الملف
     * @param mimeType نوع MIME الافتراضي
     * @param endMarker علامة نهاية الملف بالنظام الست عشري (اختياري - مهم لعملية Carving)
     * @param maxFileSize الحد الأقصى لحجم الملف بالبايت (0 = بلا حد)
     * @param matchFn دالة مطابقة مخصصة للتحقق من البايتات (تُستخدم للتوقيعات المعقدة)
     */
    data class FileSignature(
        val name: String,
        val extensions: List<String>,
        val hexPattern: ByteArray,
        val offset: Int = 0,
        val category: FileCategory,
        val mimeType: String,
        val endMarker: ByteArray? = null,
        val maxFileSize: Long = 0L,
        val matchFn: ((ByteArray) -> Boolean)? = null
    ) {
        /**
         * التحقق مما إذا كانت البايتات المعطاة تطابق هذا التوقيع
         *
         * @param header البايتات الأولى من الملف
         * @return true إذا تطابقت البايتات مع التوقيع
         */
        fun matches(header: ByteArray): Boolean {
            // إذا كانت هناك دالة مطابقة مخصصة، استخدمها أولاً
            // لأن بعض التوقيعات تحتاج منطق معقد (مثل HEIC و MP4)
            matchFn?.let { return it(header) }

            // مطابقة بسيطة: مقارنة البايتات من الإزاحة المحددة
            if (header.size < offset + hexPattern.size) return false

            for (i in hexPattern.indices) {
                if (header[offset + i] != hexPattern[i]) return false
            }
            return true
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FileSignature) return false
            return name == other.name && hexPattern.contentEquals(other.hexPattern)
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + hexPattern.contentHashCode()
            return result
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // دوال مساعدة لتحويل النص الست عشري إلى مصفوفة بايتات
    // ═══════════════════════════════════════════════════════════════

    /**
     * تحويل سلسلة نصية ست عشرية إلى مصفوفة بايتات
     * مثال: "FFD8FF" → [0xFF, 0xD8, 0xFF]
     */
    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace("\\s".toRegex(), "")
        return ByteArray(cleanHex.length / 2) { i ->
            ((Character.digit(cleanHex[2 * i], 16) shl 4) +
                    Character.digit(cleanHex[2 * i + 1], 16)).toByte()
        }
    }

    /**
     * مطابقة البايتات مع نمط ست عشري في موضع محدد
     * تستخدم للمقارنة الجزئية داخل الرأس
     */
    private fun matchesAt(header: ByteArray, offset: Int, hex: String): Boolean {
        val pattern = hexToBytes(hex)
        if (header.size < offset + pattern.size) return false
        for (i in pattern.indices) {
            if (header[offset + i] != pattern[i]) return false
        }
        return true
    }

    // ═══════════════════════════════════════════════════════════════
    // صور - Image Signatures
    // ═══════════════════════════════════════════════════════════════

    /**
     * JPEG - تنسيق الصور الأكثر شيوعاً
     * يبدأ دائماً بـ FFD8FF (SOI + APP0 أو APP1 أو غيرها)
     * ينتهي بعلامة FFD9 (EOI - End of Image) المهمة لعملية Carving
     */
    val JPEG = FileSignature(
        name = "JPEG",
        extensions = listOf("jpg", "jpeg", "jpe", "jfif"),
        hexPattern = hexToBytes("FFD8FF"),
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/jpeg",
        endMarker = hexToBytes("FFD9"),
        maxFileSize = 100L * 1024 * 1024 // 100 MB حد أقصى
    )

    /**
     * PNG - تنسيق رسومات الشبكة المحمولة
     * التوقيع ثابت دائماً: 89 50 4E 47 0D 0A 1A 0A
     * ينتهي بعلامة IEND chunk
     */
    val PNG = FileSignature(
        name = "PNG",
        extensions = listOf("png"),
        hexPattern = hexToBytes("89504E470D0A1A0A"),
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/png",
        endMarker = hexToBytes("0000000049454E44AE426082"), // IEND chunk
        maxFileSize = 150L * 1024 * 1024 // 150 MB
    )

    /**
     * GIF - تنسيق تبادل الرسومات
     * يأتي بنسختين: GIF87a و GIF89a
     */
    val GIF = FileSignature(
        name = "GIF",
        extensions = listOf("gif"),
        hexPattern = hexToBytes("47494638"),
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/gif",
        matchFn = { header ->
            // التحقق من GIF87a أو GIF89a
            matchesAt(header, 0, "474946383761") ||
            matchesAt(header, 0, "474946383961")
        },
        maxFileSize = 50L * 1024 * 1024 // 50 MB
    )

    /**
     * BMP - تنسيق الصورة النقطية لويندوز
     */
    val BMP = FileSignature(
        name = "BMP",
        extensions = listOf("bmp", "dib"),
        hexPattern = hexToBytes("424D"),
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/bmp",
        maxFileSize = 200L * 1024 * 1024
    )

    /**
     * WEBP - تنسيق صور ويب حديث من جوجل
     * يبدأ بـ RIFF ثم حجم الملف ثم WEBP
     * لذلك نحتاج دالة مطابقة مخصصة
     */
    val WEBP = FileSignature(
        name = "WEBP",
        extensions = listOf("webp"),
        hexPattern = hexToBytes("52494646"), // RIFF - سيتم التحقق الإضافي في matchFn
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/webp",
        matchFn = { header ->
            // RIFF في الإزاحة 0 و WEBP في الإزاحة 8
            matchesAt(header, 0, "52494646") && matchesAt(header, 8, "57454250")
        },
        maxFileSize = 100L * 1024 * 1024
    )

    /**
     * HEIC/HEIF - تنسيق الصور عالي الكفاءة من آبل
     * يستخدم حاوية ftyp مع نوع heic أو heix أو hevc أو hevx
     */
    val HEIC = FileSignature(
        name = "HEIC",
        extensions = listOf("heic", "heif", "heix", "hevc", "hevx"),
        hexPattern = hexToBytes("000000"), // حجم الصندوق - سيتم التحقق في matchFn
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/heic",
        matchFn = { header ->
            // التحقق من ftyp في الإزاحة 4 ثم heic/heix/hevc في الإزاحة 8
            if (header.size < 12) return@FileSignature false
            matchesAt(header, 4, "66747970") && (
                matchesAt(header, 8, "68656963") || // heic
                matchesAt(header, 8, "68656978") || // heix
                matchesAt(header, 8, "68657663") || // hevc
                matchesAt(header, 8, "68657678")    // hevx
            )
        },
        maxFileSize = 200L * 1024 * 1024
    )

    /**
     * TIFF - تنسيق ملف الصورة ذي العلامات
     * يأتي بنسختين حسب ترتيب البايتات:
     * - Little Endian: 49 49 2A 00 (II)
     * - Big Endian: 4D 4D 00 2A (MM)
     */
    val TIFF_LE = FileSignature(
        name = "TIFF_LE",
        extensions = listOf("tiff", "tif"),
        hexPattern = hexToBytes("49492A00"),
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/tiff",
        maxFileSize = 500L * 1024 * 1024
    )

    val TIFF_BE = FileSignature(
        name = "TIFF_BE",
        extensions = listOf("tiff", "tif"),
        hexPattern = hexToBytes("4D4D002A"),
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/tiff",
        maxFileSize = 500L * 1024 * 1024
    )

    // ──────────────────────────────────────────────
    // تنسيقات RAW للكاميرات الاحترافية
    // هذه التنسيقات تحتوي على بيانات الصورة الخام من مستشعر الكاميرا
    // ──────────────────────────────────────────────

    /**
     * CR2 - تنسيق RAW لكاميرات Canon
     * يعتمد على حاوية TIFF مع بيانات Canon الخاصة
     */
    val CR2 = FileSignature(
        name = "CR2",
        extensions = listOf("cr2"),
        hexPattern = hexToBytes("49492A00"), // نفس TIFF LE لكن مع فحص إضافي
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/x-canon-cr2",
        matchFn = { header ->
            // TIFF little-endian مع علامة CR2 في الإزاحة 8-10
            if (header.size < 11) return@FileSignature false
            matchesAt(header, 0, "49492A00") && matchesAt(header, 8, "43523200") // "CR2\0"
        },
        maxFileSize = 2L * 1024 * 1024 * 1024 // 2 GB
    )

    /**
     * NEF - تنسيق RAW لكاميرات Nikon
     * يعتمد أيضاً على TIFF مع بيانات Nikon الخاصة
     */
    val NEF = FileSignature(
        name = "NEF",
        extensions = listOf("nef"),
        hexPattern = hexToBytes("4D4D002A"), // TIFF Big Endian مع فحص Nikon
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/x-nikon-nef",
        matchFn = { header ->
            // Nikon يستخدم TIFF BE مع علامة خاصة
            matchesAt(header, 0, "4D4D002A") ||
            matchesAt(header, 0, "49492A00") // بعض نماذج Nikon تستخدم LE
        },
        maxFileSize = 2L * 1024 * 1024 * 1024
    )

    /**
     * ARW - تنسيق RAW لكاميرات Sony
     */
    val ARW = FileSignature(
        name = "ARW",
        extensions = listOf("arw"),
        hexPattern = hexToBytes("49492A00"),
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/x-sony-arw",
        matchFn = { header ->
            // Sony ARW يبدأ بـ TIFF LE مع بيانات Sony
            matchesAt(header, 0, "49492A00") && header.size >= 4
        },
        maxFileSize = 2L * 1024 * 1024 * 1024
    )

    /**
     * DNG - تنسيق الرقمي السلبي من Adobe (معيار مفتوح لـ RAW)
     */
    val DNG = FileSignature(
        name = "DNG",
        extensions = listOf("dng"),
        hexPattern = hexToBytes("49492A00"),
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/x-adobe-dng",
        matchFn = { header ->
            // DNG يستخدم TIFF مع علامة DNG في البيانات الوصفية
            // للتبسيط، نطابق TIFF LE مع فحص إضافي
            if (header.size < 16) return@FileSignature false
            matchesAt(header, 0, "49492A00") || matchesAt(header, 0, "4D4D002A")
        },
        maxFileSize = 2L * 1024 * 1024 * 1024
    )

    /**
     * ORF - تنسيق RAW لكاميرات Olympus
     */
    val ORF = FileSignature(
        name = "ORF",
        extensions = listOf("orf"),
        hexPattern = hexToBytes("4949524F"), // "IIRO" - Olympus specific
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/x-olympus-orf",
        matchFn = { header ->
            matchesAt(header, 0, "4949524F") || // IIRO
            matchesAt(header, 0, "49492A00")     // بعض الطرز تستخدم TIFF LE
        },
        maxFileSize = 2L * 1024 * 1024 * 1024
    )

    /**
     * RW2 - تنسيق RAW لكاميرات Panasonic Lumix
     */
    val RW2 = FileSignature(
        name = "RW2",
        extensions = listOf("rw2"),
        hexPattern = hexToBytes("49492A00"),
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/x-panasonic-rw2",
        matchFn = { header ->
            // Panasonic يستخدم TIFF LE مع علامة خاصة في الإزاحة 8
            if (header.size < 12) return@FileSignature false
            matchesAt(header, 0, "49492A00") && header[8] == 0x55.toByte() // علامة Panasonic
        },
        maxFileSize = 2L * 1024 * 1024 * 1024
    )

    /**
     * PEF - تنسيق RAW لكاميرات Pentax
     */
    val PEF = FileSignature(
        name = "PEF",
        extensions = listOf("pef"),
        hexPattern = hexToBytes("49492A00"),
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/x-pentax-pef",
        matchFn = { header ->
            matchesAt(header, 0, "49492A00") || matchesAt(header, 0, "4D4D002A")
        },
        maxFileSize = 2L * 1024 * 1024 * 1024
    )

    /**
     * RAF - تنسيق RAW لكاميرات Fujifilm
     */
    val RAF = FileSignature(
        name = "RAF",
        extensions = listOf("raf"),
        hexPattern = hexToBytes("46554649464C494D"), // "FUJIFILM"
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/x-fujifilm-raf",
        maxFileSize = 2L * 1024 * 1024 * 1024
    )

    /**
     * SRW - تنسيق RAW لكاميرات Samsung
     */
    val SRW = FileSignature(
        name = "SRW",
        extensions = listOf("srw"),
        hexPattern = hexToBytes("49492A00"),
        offset = 0,
        category = FileCategory.PHOTO,
        mimeType = "image/x-samsung-srw",
        matchFn = { header ->
            matchesAt(header, 0, "49492A00") // TIFF LE مع بيانات Samsung
        },
        maxFileSize = 2L * 1024 * 1024 * 1024
    )

    // ═══════════════════════════════════════════════════════════════
    // فيديو - Video Signatures
    // ═══════════════════════════════════════════════════════════════

    /**
     * MP4 - تنسيق الفيديو الأكثر شيوعاً
     * يستخدم حاوية ftyp مع أنواع مختلفة:
     * - mp41, mp42: MPEG-4
     * - isom: ISO Base Media
     * - M4V: iTunes Video
     * - avc1: H.264
     * - MSNV: Sony PSP
     * - dash: DASH
     */
    val MP4 = FileSignature(
        name = "MP4",
        extensions = listOf("mp4", "m4v", "m4a", "3g2", "3gp2"),
        hexPattern = hexToBytes("000000"), // حجم الصندوق - التحقق في matchFn
        offset = 0,
        category = FileCategory.VIDEO,
        mimeType = "video/mp4",
        matchFn = { header ->
            if (header.size < 12) return@FileSignature false
            // البحث عن ftyp في الإزاحة 4
            if (!matchesAt(header, 4, "66747970")) return@FileSignature false
            // فحص النوع الفرعي بعد ftyp
            matchesAt(header, 8, "6D703432") || // mp42
            matchesAt(header, 8, "69736F6D") || // isom
            matchesAt(header, 8, "6D703431") || // mp41
            matchesAt(header, 8, "4D345620") || // M4V
            matchesAt(header, 8, "61766331") || // avc1
            matchesAt(header, 8, "4D534E56") || // MSNV
            matchesAt(header, 8, "64617368") || // dash
            matchesAt(header, 8, "71742020") || // qt  (sometimes in MP4)
            matchesAt(header, 8, "46564720") || // FVG
            matchesAt(header, 8, "46564731")     // FVG1
        },
        maxFileSize = 10L * 1024 * 1024 * 1024 // 10 GB
    )

    /**
     * AVI - تنسيق الفيديو الصوتي المتشابك
     * يبدأ بـ RIFF ثم حجم الملف ثم AVI
     */
    val AVI = FileSignature(
        name = "AVI",
        extensions = listOf("avi"),
        hexPattern = hexToBytes("52494646"), // RIFF
        offset = 0,
        category = FileCategory.VIDEO,
        mimeType = "video/avi",
        matchFn = { header ->
            matchesAt(header, 0, "52494646") && matchesAt(header, 8, "41564920") // AVI
        },
        maxFileSize = 10L * 1024 * 1024 * 1024
    )

    /**
     * MKV / WebM - تنسيق ماتروسكا
     * EBML header يبدأ بـ 1A 45 DF A3
     */
    val MKV = FileSignature(
        name = "MKV",
        extensions = listOf("mkv", "mk3d", "mka", "mks", "webm"),
        hexPattern = hexToBytes("1A45DFA3"),
        offset = 0,
        category = FileCategory.VIDEO,
        mimeType = "video/x-matroska",
        maxFileSize = 50L * 1024 * 1024 * 1024 // 50 GB
    )

    /**
     * MOV - تنسيق QuickTime من آبل
     * يمكن أن يبدأ بعدة أنماط:
     * - moov (فيلم كامل)
     * - mdat (بيانات الوسائط)
     * - free (مساحة حرة)
     * - skip (بيانات يمكن تخطيها)
     * - wide (علامة توسيع)
     * - ftyp (نوع الملف)
     */
    val MOV = FileSignature(
        name = "MOV",
        extensions = listOf("mov", "qt"),
        hexPattern = hexToBytes("000000"), // التحقق في matchFn
        offset = 0,
        category = FileCategory.VIDEO,
        mimeType = "video/quicktime",
        matchFn = { header ->
            if (header.size < 8) return@FileSignature false
            // التحقق من أنماط QuickTime المختلفة في الإزاحة 4
            matchesAt(header, 4, "6D6F6F76") || // moov
            matchesAt(header, 4, "6D646174") || // mdat
            matchesAt(header, 4, "66747970") || // ftyp + qt
            (matchesAt(header, 4, "66747970") && matchesAt(header, 8, "71742020")) || // ftyp + "qt  "
            matchesAt(header, 4, "66726565") || // free
            matchesAt(header, 4, "736B6970") || // skip
            matchesAt(header, 4, "77696465")     // wide
        },
        maxFileSize = 10L * 1024 * 1024 * 1024
    )

    /**
     * FLV - تنسيق فلاش فيديو
     */
    val FLV = FileSignature(
        name = "FLV",
        extensions = listOf("flv"),
        hexPattern = hexToBytes("464C56"),
        offset = 0,
        category = FileCategory.VIDEO,
        mimeType = "video/x-flv",
        maxFileSize = 10L * 1024 * 1024 * 1024
    )

    /**
     * 3GP - تنسيق فيديو الجيل الثالث لشراكة المشروع
     * يستخدم حاوية ftyp مع نوع 3gp
     */
    val THREE_GP = FileSignature(
        name = "3GP",
        extensions = listOf("3gp", "3g2"),
        hexPattern = hexToBytes("000000"),
        offset = 0,
        category = FileCategory.VIDEO,
        mimeType = "video/3gpp",
        matchFn = { header ->
            if (header.size < 12) return@FileSignature false
            matchesAt(header, 4, "66747970") && (
                matchesAt(header, 8, "336770") || // 3gp
                matchesAt(header, 8, "336732") || // 3g2
                matchesAt(header, 8, "33677034") || // 3gp4
                matchesAt(header, 8, "33673261")    // 3g2a
            )
        },
        maxFileSize = 5L * 1024 * 1024 * 1024
    )

    /**
     * WMV / ASF - تنسيق فيديو ويندوز ميديا
     * ASF Header Object GUID: 30 26 B2 75 8E 66 CF 11 A6 D9 00 AA 00 62 CE 6C
     */
    val WMV = FileSignature(
        name = "WMV",
        extensions = listOf("wmv", "asf", "wma"),
        hexPattern = hexToBytes("3026B2758E66CF11A6D900AA0062CE6C"),
        offset = 0,
        category = FileCategory.VIDEO,
        mimeType = "video/x-ms-wmv",
        maxFileSize = 10L * 1024 * 1024 * 1024
    )

    /**
     * MPG / MPEG - تنسيق MPEG
     * يأتي بعدة أنماط:
     * - 000001BA: MPEG-2 Program Stream / MPEG-1 System
     * - 000001B3: MPEG-1/2 Video Sequence Header
     */
    val MPEG = FileSignature(
        name = "MPEG",
        extensions = listOf("mpg", "mpeg", "mpe", "m1v", "m2v", "mp2", "mpv"),
        hexPattern = hexToBytes("000001BA"),
        offset = 0,
        category = FileCategory.VIDEO,
        mimeType = "video/mpeg",
        matchFn = { header ->
            matchesAt(header, 0, "000001BA") || // MPEG Program Stream
            matchesAt(header, 0, "000001B3")    // MPEG Video Sequence Header
        },
        maxFileSize = 10L * 1024 * 1024 * 1024
    )

    // ═══════════════════════════════════════════════════════════════
    // صوت - Audio Signatures
    // ═══════════════════════════════════════════════════════════════

    /**
     * MP3 - تنسيق الصوت الأكثر شيوعاً
     * يأتي بعدة أنماط:
     * - FFFB: MPEG-1 Audio Layer 3
     * - FFF3: MPEG-2 Audio Layer 3
     * - 49443303: ID3v2 tag header ("ID3" + version 2.3)
     * - 49443304: ID3v2.4
     * - 49443300: ID3v2.0
     */
    val MP3 = FileSignature(
        name = "MP3",
        extensions = listOf("mp3"),
        hexPattern = hexToBytes("FFFB"),
        offset = 0,
        category = FileCategory.AUDIO,
        mimeType = "audio/mpeg",
        matchFn = { header ->
            if (header.size < 3) return@FileSignature false
            // MPEG frame sync (11 bits set)
            ((header[0].toInt() and 0xFF) == 0xFF &&
                (header[1].toInt() and 0xE0) == 0xE0) ||
            matchesAt(header, 0, "494433") // ID3 tag
        },
        maxFileSize = 500L * 1024 * 1024
    )

    /**
     * WAV - تنسيق موجة صوتية
     * يبدأ بـ RIFF ثم الحجم ثم WAVE
     */
    val WAV = FileSignature(
        name = "WAV",
        extensions = listOf("wav", "wave"),
        hexPattern = hexToBytes("52494646"), // RIFF
        offset = 0,
        category = FileCategory.AUDIO,
        mimeType = "audio/wav",
        matchFn = { header ->
            matchesAt(header, 0, "52494646") && matchesAt(header, 8, "57415645") // WAVE
        },
        maxFileSize = 5L * 1024 * 1024 * 1024
    )

    /**
     * FLAC - تنسيق ترميز الصوت بدون فقدان
     */
    val FLAC = FileSignature(
        name = "FLAC",
        extensions = listOf("flac"),
        hexPattern = hexToBytes("664C6143"), // "fLaC"
        offset = 0,
        category = FileCategory.AUDIO,
        mimeType = "audio/flac",
        maxFileSize = 2L * 1024 * 1024 * 1024
    )

    /**
     * AAC - ترميز الصوت المتقدم
     * ADTS header: FFF1 (MPEG-4) أو FFF9 (MPEG-2)
     */
    val AAC = FileSignature(
        name = "AAC",
        extensions = listOf("aac", "m4a"),
        hexPattern = hexToBytes("FFF1"),
        offset = 0,
        category = FileCategory.AUDIO,
        mimeType = "audio/aac",
        matchFn = { header ->
            matchesAt(header, 0, "FFF1") || // AAC MPEG-4
            matchesAt(header, 0, "FFF9") || // AAC MPEG-2
            matchesAt(header, 0, "FFF8")     // AAC MPEG-2 private
        },
        maxFileSize = 500L * 1024 * 1024
    )

    /**
     * OGG - حاوية OGG لتنسيقات Vorbis و Opus
     */
    val OGG = FileSignature(
        name = "OGG",
        extensions = listOf("ogg", "oga", "opus"),
        hexPattern = hexToBytes("4F676753"), // "OggS"
        offset = 0,
        category = FileCategory.AUDIO,
        mimeType = "audio/ogg",
        maxFileSize = 500L * 1024 * 1024
    )

    // ═══════════════════════════════════════════════════════════════
    // مستندات - Document Signatures
    // ═══════════════════════════════════════════════════════════════

    /**
     * PDF - تنسيق المستندات المحمولة
     */
    val PDF = FileSignature(
        name = "PDF",
        extensions = listOf("pdf"),
        hexPattern = hexToBytes("25504446"), // "%PDF"
        offset = 0,
        category = FileCategory.DOCUMENT,
        mimeType = "application/pdf",
        endMarker = hexToBytes("2525454F46"), // %%EOF
        maxFileSize = 2L * 1024 * 1024 * 1024
    )

    /**
     * OLE2 - تنسيق الملفات الثنائية القديم لمايكروسوفت
     * يستخدم في: DOC, XLS, PPT
     * D0 CF 11 E0 A1 B1 1A E1
     */
    val OLE2 = FileSignature(
        name = "OLE2",
        extensions = listOf("doc", "xls", "ppt", "dot", "xlt", "pot"),
        hexPattern = hexToBytes("D0CF11E0A1B11AE1"),
        offset = 0,
        category = FileCategory.DOCUMENT,
        mimeType = "application/msword",
        maxFileSize = 500L * 1024 * 1024
    )

    /**
     * DOCX - تنسيق Word الحديث (Office Open XML)
     * ملف ZIP يحتوي على word/ directory
     */
    val DOCX = FileSignature(
        name = "DOCX",
        extensions = listOf("docx", "docm", "dotx", "dotm"),
        hexPattern = hexToBytes("504B0304"), // PK - ZIP header
        offset = 0,
        category = FileCategory.DOCUMENT,
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        matchFn = { header ->
            // PK header + فحص وجود word/ في المحتوى
            // هذا فحص أولي - الفحص الكامل يتطلب قراءة محتويات ZIP
            matchesAt(header, 0, "504B0304")
        },
        maxFileSize = 500L * 1024 * 1024
    )

    /**
     * XLSX - تنسيق Excel الحديث
     * ملف ZIP يحتوي على xl/ directory
     */
    val XLSX = FileSignature(
        name = "XLSX",
        extensions = listOf("xlsx", "xlsm", "xltm", "xltx"),
        hexPattern = hexToBytes("504B0304"),
        offset = 0,
        category = FileCategory.DOCUMENT,
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        matchFn = { header ->
            matchesAt(header, 0, "504B0304")
        },
        maxFileSize = 500L * 1024 * 1024
    )

    /**
     * PPTX - تنسيق PowerPoint الحديث
     * ملف ZIP يحتوي على ppt/ directory
     */
    val PPTX = FileSignature(
        name = "PPTX",
        extensions = listOf("pptx", "pptm", "potx", "potm", "ppsx", "ppsm"),
        hexPattern = hexToBytes("504B0304"),
        offset = 0,
        category = FileCategory.DOCUMENT,
        mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        matchFn = { header ->
            matchesAt(header, 0, "504B0304")
        },
        maxFileSize = 2L * 1024 * 1024 * 1024
    )

    /**
     * RTF - تنسيق النص الغني
     */
    val RTF = FileSignature(
        name = "RTF",
        extensions = listOf("rtf"),
        hexPattern = hexToBytes("7B5C72746631"), // "{\rtf1"
        offset = 0,
        category = FileCategory.DOCUMENT,
        mimeType = "application/rtf",
        maxFileSize = 100L * 1024 * 1024
    )

    /**
     * TXT - ملف نصي عادي
     * لا يوجد توقيع محدد - يتم التعرف عليه بالاستبعاد
     * لكننا نتحقق من أن المحتوى أحرف ASCII/UTF-8 مقروءة
     */
    val TXT = FileSignature(
        name = "TXT",
        extensions = listOf("txt", "log", "csv", "json", "xml", "html", "css", "js"),
        hexPattern = ByteArray(0), // لا يوجد توقيع ثابت
        offset = 0,
        category = FileCategory.DOCUMENT,
        mimeType = "text/plain",
        matchFn = { header ->
            // فحص إذا كانت البايتات الأولى أحرف ASCII قابلة للقراءة
            if (header.isEmpty()) return@FileSignature false
            var printableCount = 0
            for (b in header.take(minOf(header.size, 512))) {
                val byteVal = b.toInt() and 0xFF
                if (byteVal in 0x20..0x7E || byteVal == 0x0A || byteVal == 0x0D || byteVal == 0x09) {
                    printableCount++
                }
            }
            // إذا كان أكثر من 90% من البايتات قابلة للقراءة، فهو نصي على الأرجح
            printableCount.toFloat() / minOf(header.size, 512) > 0.9f
        },
        maxFileSize = 100L * 1024 * 1024
    )

    // ═══════════════════════════════════════════════════════════════
    // أرشيفات - Archive Signatures
    // ═══════════════════════════════════════════════════════════════

    /**
     * ZIP - تنسيق الأرشيف الأكثر شيوعاً
     * يستخدم أيضاً في: DOCX, XLSX, PPTX, APK, JAR, ODT, etc.
     */
    val ZIP = FileSignature(
        name = "ZIP",
        extensions = listOf("zip", "jar", "odt", "ods", "odp", "apk", "ipa"),
        hexPattern = hexToBytes("504B0304"),
        offset = 0,
        category = FileCategory.ARCHIVE,
        mimeType = "application/zip",
        endMarker = hexToBytes("504B0506"), // End of central directory
        maxFileSize = 50L * 1024 * 1024 * 1024
    )

    /**
     * RAR - تنسيق أرشيف RAR
     * RAR4: 52 61 72 21 1A 07 00
     * RAR5: 52 61 72 21 1A 07 01 00
     */
    val RAR = FileSignature(
        name = "RAR",
        extensions = listOf("rar"),
        hexPattern = hexToBytes("526172211A07"),
        offset = 0,
        category = FileCategory.ARCHIVE,
        mimeType = "application/x-rar-compressed",
        matchFn = { header ->
            matchesAt(header, 0, "526172211A0700") || // RAR4
            matchesAt(header, 0, "526172211A070100")   // RAR5
        },
        maxFileSize = 50L * 1024 * 1024 * 1024
    )

    /**
     * 7Z - تنسيق أرشيف 7-Zip
     */
    val SEVEN_Z = FileSignature(
        name = "7Z",
        extensions = listOf("7z"),
        hexPattern = hexToBytes("377ABCAF271C"),
        offset = 0,
        category = FileCategory.ARCHIVE,
        mimeType = "application/x-7z-compressed",
        maxFileSize = 50L * 1024 * 1024 * 1024
    )

    /**
     * TAR - تنسيق أرشيف يونكس
     * التوقيع "ustar" يظهر في الإزاحة 257
     */
    val TAR = FileSignature(
        name = "TAR",
        extensions = listOf("tar"),
        hexPattern = hexToBytes("7573746172"), // "ustar" at offset 257
        offset = 257,
        category = FileCategory.ARCHIVE,
        mimeType = "application/x-tar",
        maxFileSize = 50L * 1024 * 1024 * 1024
    )

    /**
     * GZ / GZIP - تنسيق ضغط GNU
     */
    val GZ = FileSignature(
        name = "GZ",
        extensions = listOf("gz", "gzip", "tgz"),
        hexPattern = hexToBytes("1F8B"),
        offset = 0,
        category = FileCategory.ARCHIVE,
        mimeType = "application/gzip",
        maxFileSize = 50L * 1024 * 1024 * 1024
    )

    // ═══════════════════════════════════════════════════════════════
    // APK - Android Package
    // ═══════════════════════════════════════════════════════════════

    /**
     * APK - حزمة تطبيق أندرويد
     * ملف ZIP يحتوي على AndroidManifest.xml
     * يحتاج فحصاً إضافياً للتمييز عن ملفات ZIP العادية
     */
    val APK = FileSignature(
        name = "APK",
        extensions = listOf("apk"),
        hexPattern = hexToBytes("504B0304"),
        offset = 0,
        category = FileCategory.APK,
        mimeType = "application/vnd.android.package-archive",
        matchFn = { header ->
            // فحص أولي - PK header
            // الفحص الكامل يتطلب التحقق من AndroidManifest.xml داخل ZIP
            matchesAt(header, 0, "504B0304")
        },
        maxFileSize = 5L * 1024 * 1024 * 1024
    )

    // ═══════════════════════════════════════════════════════════════
    // مجموعات التوقيعات المجمعة حسب الفئة
    // ═══════════════════════════════════════════════════════════════

    /**
     * جميع توقيعات الصور بما فيها تنسيقات RAW
     */
    val IMAGE_SIGNATURES: List<FileSignature> = listOf(
        JPEG, PNG, GIF, BMP, WEBP, HEIC,
        TIFF_LE, TIFF_BE,
        CR2, NEF, ARW, DNG, ORF, RW2, PEF, RAF, SRW
    )

    /**
     * جميع توقيعات الفيديو
     */
    val VIDEO_SIGNATURES: List<FileSignature> = listOf(
        MP4, AVI, MKV, MOV, FLV, THREE_GP, WMV, MPEG
    )

    /**
     * جميع توقيعات الصوت
     */
    val AUDIO_SIGNATURES: List<FileSignature> = listOf(
        MP3, WAV, FLAC, AAC, OGG
    )

    /**
     * جميع توقيعات المستندات
     */
    val DOCUMENT_SIGNATURES: List<FileSignature> = listOf(
        PDF, OLE2, DOCX, XLSX, PPTX, RTF, TXT
    )

    /**
     * جميع توقيعات الأرشيفات
     */
    val ARCHIVE_SIGNATURES: List<FileSignature> = listOf(
        ZIP, RAR, SEVEN_Z, TAR, GZ
    )

    /**
     * توقيعات APK
     */
    val APK_SIGNATURES: List<FileSignature> = listOf(APK)

    /**
     * جميع التوقيعات مجتمعة
     */
    val ALL_SIGNATURES: List<FileSignature> =
        IMAGE_SIGNATURES + VIDEO_SIGNATURES + AUDIO_SIGNATURES +
        DOCUMENT_SIGNATURES + ARCHIVE_SIGNATURES + APK_SIGNATURES

    /**
     * الحصول على التوقيعات حسب فئة الملف
     *
     * @param category فئة الملف المطلوبة
     * @return قائمة التوقيعات المطابقة للفئة
     */
    fun getSignaturesByCategory(category: FileCategory): List<FileSignature> =
        when (category) {
            FileCategory.PHOTO     -> IMAGE_SIGNATURES
            FileCategory.VIDEO     -> VIDEO_SIGNATURES
            FileCategory.AUDIO     -> AUDIO_SIGNATURES
            FileCategory.DOCUMENT  -> DOCUMENT_SIGNATURES
            FileCategory.ARCHIVE   -> ARCHIVE_SIGNATURES
            FileCategory.APK       -> APK_SIGNATURES
            FileCategory.OTHER     -> ALL_SIGNATURES
        }

    /**
     * تحديد نوع الملف من خلال البايتات الأولى
     *
     * هذه الدالة الأساسية المستخدمة في عملية المسح - تقرأ رأس الملف
     * وتقارنه مع جميع التوقيعات المعروفة لتحديد النوع
     *
     * @param header البايتات الأولى من الملف (يُفضل 32 بايت على الأقل)
     * @param category فئة اختيارية لتضييق نطاق البحث
     * @return التوقيع المطابق أو null إذا لم يتم التعرف على النوع
     */
    fun identifyFileType(header: ByteArray, category: FileCategory? = null): FileSignature? {
        val signatures = if (category != null) {
            getSignaturesByCategory(category)
        } else {
            ALL_SIGNATURES
        }

        // مطابقة التوقيعات ذات الدوال المخصصة أولاً لأنها أكثر دقة
        val (custom, simple) = signatures.partition { it.matchFn != null }

        for (sig in custom) {
            if (sig.matches(header)) return sig
        }

        for (sig in simple) {
            if (sig.matches(header)) return sig
        }

        return null
    }

    /**
     * التحقق مما إذا كان الملف من نوع APK
     * يحتاج فحصاً خاصاً لأن APK هو ملف ZIP مع AndroidManifest.xml
     *
     * @param filePath مسار الملف للفحص
     * @return true إذا كان الملف APK صالحاً
     */
    suspend fun isApkFile(filePath: String): Boolean {
        // محاولة فتح الملف كـ ZIP والبحث عن AndroidManifest.xml
        return try {
            val file = java.io.File(filePath)
            if (!file.exists() || !file.canRead()) return false

            // قراءة الرأس أولاً
            val header = ByteArray(4)
            file.inputStream().use { input ->
                val bytesRead = input.read(header)
                if (bytesRead < 4) return false
                if (!ZIP.matches(header)) return false
            }

            // فحص وجود AndroidManifest.xml داخل الأرشيف
            // باستخدام ZipFile
            java.util.zip.ZipFile(file).use { zipFile ->
                zipFile.getEntry("AndroidManifest.xml") != null
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * الحصول على قائمة بجميع الامتدادات المدعومة
     */
    fun getAllSupportedExtensions(): Set<String> =
        ALL_SIGNATURES.flatMap { it.extensions }.toSet()

    /**
     * البحث عن التوقيع حسب اسم الامتداد
     *
     * @param extension امتداد الملف بدون النقطة (مثال: "jpg")
     * @return التوقيع المطابق أو null
     */
    fun getSignatureByExtension(extension: String): FileSignature? {
        val ext = extension.lowercase().removePrefix(".")
        return ALL_SIGNATURES.find { ext in it.extensions }
    }

    /**
     * حساب الحد الأدنى لحجم الرأس المطلوب لفحص جميع التوقيعات
     * هذا مهم لتحسين الأداء - نقرأ فقط القدر المطلوب من بداية الملف
     */
    val MINIMUM_HEADER_SIZE: Int by lazy {
        // أكبر إزاحة + حجم نمط بين جميع التوقيعات
        // TAR يحتاج 262 بايت (offset 257 + 5 bytes)
        // لكن للدوال المخصصة قد نحتاج أكثر
        // نستخدم 512 بايت كحد آمن
        512
    }
}
