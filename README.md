<div dir="rtl">

<div align="center">

# 🛡️ Ultimate Recovery Pro

[![Version](https://img.shields.io/badge/الإصدار-1.0.0-blue.svg)](https://github.com/ultimaterecovery/pro)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-29%20(Android%2010)-green.svg)](https://developer.android.com/about/versions/10)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-orange.svg)](https://developer.android.com/about/versions/14)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/الترخيص-Proprietary-red.svg)](LICENSE)

### تطبيق احترافي متقدم لاستعادة الملفات المحذوفة على أندرويد

تطبيق شامل لاستعادة الصور والفيديوهات والملفات والرسائل وسجل المكالمات وبيانات التطبيقات
مع دعم كامل للروت، فحص متعدد المستويات، تشفير AES-256، نسخ احتياطي سحابي، ومساعد ذكي مدعوم بالذكاء الاصطناعي.

[الميزات الرئيسية](#-الميزات-الرئيسية) • [البنية التقنية](#-البنية-التقنية) • [طريقة البناء](#-طريقة-البناء) • [المكتبات المستخدمة](#-المكتبات-المستخدمة)

</div>

---

## 📋 جدول المحتويات

1. [نظرة عامة](#-نظرة-عامة)
2. [الميزات الرئيسية](#-الميزات-الرئيسية)
3. [البنية التقنية](#-البنية-التقنية)
4. [هيكل المشروع](#-هيكل-المشروع)
5. [المتطلبات](#-المتطلبات)
6. [طريقة البناء](#-طريقة-البناء)
7. [المكتبات المستخدمة](#-المكتبات-المستخدمة)
8. [واجهة برمجة التطبيق](#-واجهة-برمجة-التطبيق)
9. [نظام الصلاحيات](#-نظام-الصلاحيات)
10. [نظام الروت](#-نظام-الروت)
11. [نظام الفحص](#-نظام-الفحص)
12. [نظام التشفير والحماية](#-نظام-التشفير-والحماية)
13. [خريطة الطريق](#-خريطة-الطريق)
14. [الترخيص](#-الترخيص)

---

## 🔍 نظرة عامة

**Ultimate Recovery Pro** هو تطبيق أندرويد احترافي متقدم مصمم لاستعادة الملفات المحذوفة بأعلى معدل نجاح ممكن. يجمع التطبيق بين تقنيات المسح المتعددة (سريع، عميق، بالتوقيعات، خام) مع دعم كامل لصلاحيات الروت للوصول المباشر إلى التخزين الخام.

### الأهداف الرئيسية

- 🎯 **أعلى معدل استعادة ممكن** عبر تقنيات مسح متعددة المستويات
- 🔐 **أمان متقدم** مع تشفير AES-256-GCM ومصادقة بيومترية
- 🤖 **ذكاء اصطناعي** لتحليل الملفات وتصنيفها واقتراح استراتيجيات الإصلاح
- ☁️ **نسخ احتياطي متكامل** محلي وسحابي (Google Drive و Dropbox)
- ♻️ **سلة محذوفات ذكية** كشبكة أمان للحذف العرضي
- 📱 **دعم الروت الاختياري** لفتح إمكانيات استعادة متقدمة

---

## ⭐ الميزات الرئيسية

### 📸 استعادة الصور
| التنسيق | الوصف |
|---------|-------|
| JPG/JPEG | تنسيق الصور الأكثر شيوعاً |
| PNG | رسومات الشبكة المحمولة |
| WEBP | تنسيق صور ويب من جوجل |
| HEIC/HEIF | صور عالية الكفاءة من آبل |
| GIF | تنسيق تبادل الرسومات |
| BMP | صور نقطية لويندوز |
| TIFF | ملفات صور ذات علامات |
| RAW (CR2/NEF/ARW/DNG/ORF/RW2/PEF/RAF/SRW) | تنسيقات خام لكاميرات احترافية |

### 🎬 استعادة الفيديوهات
| التنسيق | الوصف |
|---------|-------|
| MP4/M4V | تنسيق الفيديو الأكثر شيوعاً |
| MKV/WebM | تنسيق ماتروسكا المفتوح |
| AVI | تنسيق الفيديو الصوتي المتشابك |
| MOV | تنسيق QuickTime من آبل |
| 3GP/3G2 | فيديو الجيل الثالث للهواتف |
| FLV | فيديو فلاش |
| WMV/ASF | فيديو ويندوز ميديا |
| MPEG/MPG | تنسيق MPEG القياسي |

### 📁 استعادة الملفات
| التنسيق | الوصف |
|---------|-------|
| PDF | مستندات محمولة |
| DOC/DOCX | مستندات Word |
| XLS/XLSX | جداول Excel |
| PPT/PPTX | عروض PowerPoint |
| ZIP/RAR/7Z/TAR/GZ | أرشيفات مضغوطة |
| TXT/CSV/JSON/XML | ملفات نصية وبيانات |
| APK | حزم تطبيقات أندرويد |

### 💬 استعادة الرسائل النصية SMS
- استعادة الرسائل المحذوفة عبر ContentProvider
- دعم استعادة المحادثات الكاملة مع بيانات المرسل
- تصدير الرسائل إلى JSON للنسخ الاحتياطي

### 📞 استعادة سجل المكالمات
- استعادة سجلات المكالمات الصادرة والواردة والفائتة
- عرض مدة المكالمات وأرقام المتصلين
- تصدير سجل المكالمات إلى JSON

### 📦 استعادة بيانات التطبيقات
- الوصول إلى بيانات التطبيقات المثبتة (يتطلب روت)
- استعادة التفضيلات المشتركة (SharedPreferences)
- استعادة قواعد بيانات التطبيقات الداخلية

### 👤 استعادة الحسابات والبيانات القديمة
- استعادة بيانات الحسابات المرتبطة بالجهاز
- الوصول إلى بيانات المصادقة القديمة
- استعادة معلومات الملفات الشخصية

### 🔧 دعم الروت الكامل
| الميزة | الوصف |
|--------|-------|
| Deep Scan | مسح عميق للكتل الخام من التخزين |
| Raw Recovery | استعادة خام بايت ببايت من الأقسام |
| Partition Scan | اكتشاف ومسح جميع أقسام التخزين |
| Direct Disk Access | وصول مباشر للقرص عبر أوامر su |
| Magisk Support | دعم كامل لـ Magisk |
| SuperSU Support | دعم SuperSU |

### 🔬 نظام الفحص المتقدم
| نوع الفحص | السرعة | العمق | يتطلب روت |
|-----------|--------|-------|-----------|
| Quick Scan | ⚡ سريع | سطحياً | لا |
| Deep Scan | 🔄 متوسط | عميق | نعم |
| Signature Recovery | 🎯 متوسط | متوسط | لا |
| Raw Scan | 🐢 بطيء | كامل | نعم |
| Partition Scan | 🐢 بطيء جداً | شامل | نعم |

### ☁️ النسخ الاحتياطي
- **نسخ محلي**: حفظ على التخزين الداخلي بتنسيق ZIP
- **Google Drive**: رفع تلقائي إلى Google Drive
- **Dropbox**: رفع تلقائي إلى Dropbox
- **تشفير اختياري**: AES-256-GCM مع PBKDF2
- **نسخ تزايدي**: دعم النسخ التزايدي لتوفير المساحة
- **جدولة**: نسخ يومي/أسبوعي/شهري عبر WorkManager

### ♻️ سلة المحذوفات الذكية
- اعتراض الملفات المحذوفة تلقائياً عبر FileMonitorService
- فترة انتهاء قابلة للتعديل (7/14/30/60/90 يوم)
- إخلاء تلقائي باستخدام استراتيجية LRU
- تصنيف تلقائي حسب نوع الملف
- حذف آمن متوافق مع DoD 5220.22-M
- بحث متقدم في المحذوفات

### 📂 مدير الملفات الاحترافي
- تصفح نظام الملفات الكامل
- عرض تفصيلي لخصائص الملفات
- دعم العمليات الأساسية (نسخ، نقل، حذف، إعادة تسمية)
- عرض مساحة التخزين والتحليل

### 🤖 AI Recovery Assistant
- **تصنيف الملفات**: تحديد تلقائي للنوع عبر Magic Numbers و MIME
- **كشف التلف**: تحليل هيكلي لتحديد حالة الملف
- **اقتراحات الإصلاح**: استراتيجيات إصلاح ذكية حسب نوع الملف
- **تسمية ذكية**: إنشاء أسماء ذات معنى للملفات المستعادة
- **كشف التكرار**: مقارنة MD5 + SHA-256 لإيجاد الملفات المكررة
- **تحليل التخزين**: رؤى شاملة حول استخدام التخزين
- **تسجيل أولوية الاستعادة**: ترتيب الملفات حسب الأهمية (0-100)
- **توصيات المسح**: اقتراح نوع المسح الأمثل حسب النتائج
- **تجميع ذكي**: تنظيم الملفات حسب الفئة والتاريخ والحجم

### 🔐 الحماية والتشفير
- **تشفير الملفات**: AES-256-GCM مع مفتاح مشتق من PBKDF2 (100,000 تكرار)
- **قفل التطبيق**: PIN / كلمة مرور / بصمة إصبع
- **مصادقة بيومترية**: دعم البصمة والتعرف على الوجه
- **حذف آمن**: DoD 5220.22-M (3 مرات كتابة فوق البيانات)
- **Android Keystore**: تخزين المفاتيح في Keystore الآمن
- **EncryptedSharedPreferences**: تخزين مشفر للإعدادات الحساسة
- **التحقق من السلامة**: MD5 و SHA-256 للتحقق من سلامة الملفات

---

## 🏗️ البنية التقنية

### مخطط MVVM Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │Activity  │  │Fragment  │  │Custom    │  │Dialog    │   │
│  │          │  │          │  │View      │  │          │   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘   │
│       │              │              │              │         │
│  ┌────▼──────────────▼──────────────▼──────────────▼─────┐  │
│  │                    ViewBinding                         │  │
│  └───────────────────────┬───────────────────────────────┘  │
└──────────────────────────┼──────────────────────────────────┘
                           │
┌──────────────────────────┼──────────────────────────────────┐
│                    ViewModel Layer                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ScanVM    │  │PhotoVM   │  │BackupVM  │  │SettingsVM│   │
│  │          │  │          │  │          │  │          │   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘   │
│       │              │              │              │         │
│  ┌────▼──────────────▼──────────────▼──────────────▼─────┐  │
│  │              LiveData / StateFlow                     │  │
│  └───────────────────────┬───────────────────────────────┘  │
└──────────────────────────┼──────────────────────────────────┘
                           │
┌──────────────────────────┼──────────────────────────────────┐
│                    Domain Layer                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ScanEngine│  │Recovery  │  │AI        │  │Backup    │   │
│  │          │  │Engine    │  │Assistant │  │Manager   │   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘   │
│       │              │              │              │         │
│  ┌────▼──────────────▼──────────────▼──────────────▼─────┐  │
│  │              Repository Pattern                       │  │
│  └───────────────────────┬───────────────────────────────┘  │
└──────────────────────────┼──────────────────────────────────┘
                           │
┌──────────────────────────┼──────────────────────────────────┐
│                    Data Layer                                │
│  ┌────────────┐  ┌────────────┐  ┌────────────────────┐    │
│  │Room DB     │  │SharedPrefs │  │ContentProvider     │    │
│  │(9 DAOs)    │  │(Encrypted) │  │(SMS/CallLog/Media) │    │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────────────┘    │
│        │               │               │                     │
│  ┌─────▼───────────────▼───────────────▼─────────────────┐  │
│  │              File System / Cloud APIs                  │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### حزمة التقنيات

| التقنية | الإصدار | الغرض |
|---------|---------|-------|
| Kotlin | 1.9.22 | لغة البرمجة الرئيسية |
| Android SDK | 34 (Android 14) | المنصة المستهدفة |
| Min SDK | 29 (Android 10) | الحد الأدنى المدعوم |
| Gradle | 8.2.2 | نظام البناء |
| Hilt | 2.50 | حقن التبعيات |
| Room | 2.6.1 | قاعدة البيانات المحلية |
| Coroutines | 1.7.3 | البرمجة غير المتزامنة |
| Navigation | 2.7.6 | التنقل بين الشاشات |
| WorkManager | 2.9.0 | المهام في الخلفية |
| libsu | 5.0.5 | عمليات الروت |

### هيكل الوحدات

```
UltimateRecoveryPro/
├── app/                          # وحدة التطبيق الرئيسية
│   ├── engine/                   # محركات الفحص والاستعادة
│   │   ├── scanner/              # ماسحات مختلفة
│   │   ├── recovery/             # محرك الاستعادة
│   │   ├── root/                 # ميزات الروت
│   │   └── signatures/           # توقيعات الملفات
│   ├── data/                     # طبقة البيانات
│   │   ├── local/                # تخزين محلي
│   │   │   ├── database/         # Room Database
│   │   │   ├── dao/              # كائنات الوصول للبيانات
│   │   │   ├── entity/           # كيانات الجداول
│   │   │   └── converter/        # محولات الأنواع
│   │   └── repository/           # مستودعات البيانات
│   ├── ui/                       # طبقة العرض
│   │   ├── activities/           # الأنشطة
│   │   ├── fragments/            # الأجزاء
│   │   ├── viewmodel/            # نماذج العرض
│   │   ├── widgets/              # عناصر واجهة مخصصة
│   │   ├── animations/           # رسوم متحركة
│   │   └── theme/                # سمات التصميم
│   ├── service/                  # خدمات الخلفية
│   ├── utils/                    # أدوات مساعدة
│   │   ├── ai/                   # مساعد الذكاء الاصطناعي
│   │   ├── backup/               # النسخ الاحتياطي
│   │   ├── crypto/               # التشفير والحماية
│   │   ├── recyclebin/           # سلة المحذوفات
│   │   ├── storage/              # أدوات التخزين
│   │   └── permission/           # إدارة الصلاحيات
│   └── manager/                  # مديرو النظام
```

---

## 📁 هيكل المشروع

```
UltimateRecoveryPro/
├── build.gradle.kts                         # ملف البناء الرئيسي
├── settings.gradle.kts                      # إعدادات Gradle
├── gradle.properties                        # خصائص Gradle
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties        # إعدادات Gradle Wrapper
│
└── app/
    ├── build.gradle.kts                     # ملف بناء التطبيق
    ├── proguard-rules.pro                   # قواعد ProGuard
    │
    └── src/main/
        ├── AndroidManifest.xml              # ملف الأندرويد الرئيسي
        │
        ├── res/                             # الموارد
        │   ├── layout/                      # تخطيطات الواجهة (30+ ملف)
        │   │   ├── activity_main.xml
        │   │   ├── activity_scan.xml
        │   │   ├── activity_lock.xml
        │   │   ├── activity_preview.xml
        │   │   ├── fragment_home.xml
        │   │   ├── fragment_scan.xml
        │   │   ├── fragment_photo_recovery.xml
        │   │   ├── fragment_video_recovery.xml
        │   │   ├── fragment_file_recovery.xml
        │   │   ├── fragment_sms_recovery.xml
        │   │   ├── fragment_call_log_recovery.xml
        │   │   ├── fragment_app_data_recovery.xml
        │   │   ├── fragment_backup.xml
        │   │   ├── fragment_recycle_bin.xml
        │   │   ├── fragment_file_manager.xml
        │   │   ├── fragment_settings.xml
        │   │   ├── dialog_backup_create.xml
        │   │   ├── dialog_filter.xml
        │   │   ├── item_recovered_photo.xml
        │   │   ├── item_recovered_video.xml
        │   │   ├── item_recovered_file.xml
        │   │   ├── item_sms_message.xml
        │   │   ├── item_call_log.xml
        │   │   ├── item_app_data.xml
        │   │   ├── item_backup.xml
        │   │   ├── item_recycle_bin.xml
        │   │   ├── item_file_entry.xml
        │   │   ├── widget_scan_progress.xml
        │   │   └── widget_storage_info.xml
        │   ├── menu/                         # قوائم التطبيق
        │   │   ├── bottom_nav_menu.xml
        │   │   ├── menu_toolbar.xml
        │   │   ├── menu_selection.xml
        │   │   └── menu_file_context.xml
        │   ├── navigation/                   # رسم التنقل
        │   │   └── nav_graph.xml
        │   ├── anim/                         # الرسوم المتحركة
        │   │   ├── fade_in.xml
        │   │   ├── fade_out.xml
        │   │   ├── scale_in.xml
        │   │   ├── pulse.xml
        │   │   ├── slide_in_right.xml
        │   │   ├── slide_in_bottom.xml
        │   │   └── slide_out_bottom.xml
        │   ├── xml/                          # ملفات XML خاصة
        │   │   ├── network_security_config.xml
        │   │   ├── file_paths.xml
        │   │   └── backup_rules.xml
        │   ├── color/                        # ألوان ديناميكية
        │   │   ├── card_stroke_color.xml
        │   │   ├── chip_background_color.xml
        │   │   └── button_tint_color.xml
        │   ├── values/                       # قيم الموارد
        │   │   ├── strings.xml
        │   │   ├── colors.xml
        │   │   ├── themes.xml
        │   │   ├── dimens.xml
        │   │   └── attrs.xml
        │   └── values-ar/                    # ترجمة عربية
        │       └── strings.xml
        │
        └── java/com/ultimaterecovery/pro/
            │
            ├── UltimateRecoveryApplication.kt    # فئة التطبيق الرئيسية
            │
            ├── engine/                           # 🔧 محركات المعالجة
            │   ├── scanner/                      # ماسحات الملفات
            │   │   ├── ScanEngine.kt             #   محرك المسح الرئيسي
            │   │   ├── QuickScanner.kt           #   مسح سريع
            │   │   ├── DeepScanner.kt            #   مسح عميق
            │   │   ├── SignatureScanner.kt       #   مسح بالتوقيعات
            │   │   └── MediaScanner.kt           #   مسح الوسائط
            │   ├── recovery/                     # محرك الاستعادة
            │   │   ├── FileRecoveryEngine.kt     #   محرك استعادة الملفات
            │   │   ├── RecoveryResult.kt         #   نتائج الاستعادة
            │   │   └── ...
            │   ├── root/                         # ميزات الروت
            │   │   ├── RootManager.kt            #   مدير صلاحيات الروت
            │   │   ├── RootScanner.kt            #   ماسح الروت
            │   │   ├── RawRecoveryEngine.kt      #   محرك الاستعادة الخام
            │   │   ├── PartitionManager.kt       #   مدير الأقسام
            │   │   └── SmsCallLogRecovery.kt     #   استعادة SMS وسجل المكالمات
            │   └── signatures/                   # توقيعات الملفات
            │       └── FileSignatures.kt         #   قاعدة بيانات التوقيعات (40+ توقيع)
            │
            ├── data/                             # 💾 طبقة البيانات
            │   ├── local/
            │   │   ├── database/
            │   │   │   └── UltimateRecoveryDatabase.kt  # قاعدة بيانات Room
            │   │   ├── dao/                      # كائنات الوصول للبيانات
            │   │   │   ├── RecoveredFileDao.kt
            │   │   │   ├── ScanSessionDao.kt
            │   │   │   ├── SmsMessageDao.kt
            │   │   │   ├── CallLogDao.kt
            │   │   │   ├── AppDataDao.kt
            │   │   │   ├── AccountDataDao.kt
            │   │   │   ├── BackupDao.kt
            │   │   │   ├── RecycleBinItemDao.kt
            │   │   │   └── RecoveryHistoryDao.kt
            │   │   ├── entity/                   # كيانات الجداول
            │   │   │   ├── RecoveredFileEntity.kt
            │   │   │   ├── ScanSessionEntity.kt
            │   │   │   ├── SmsMessageEntity.kt
            │   │   │   ├── CallLogEntity.kt
            │   │   │   ├── AppDataEntity.kt
            │   │   │   ├── AccountDataEntity.kt
            │   │   │   ├── BackupEntity.kt
            │   │   │   ├── RecycleBinItemEntity.kt
            │   │   │   └── RecoveryHistoryEntity.kt
            │   │   └── converter/
            │   │       └── EnumTypeConverters.kt
            │   └── repository/                   # مستودعات البيانات
            │       ├── RecoveredFileRepository.kt
            │       ├── ScanSessionRepository.kt
            │       ├── SmsRepository.kt
            │       ├── CallLogRepository.kt
            │       ├── AppDataRepository.kt
            │       ├── AccountRepository.kt
            │       ├── BackupRepository.kt
            │       ├── RecycleBinRepository.kt
            │       ├── RecoveryHistoryRepository.kt
            │       └── Resource.kt               # غلاف حالة المورد
            │
            ├── ui/                               # 🎨 طبقة العرض
            │   ├── activities/                   # الأنشطة
            │   │   ├── MainActivity.kt
            │   │   ├── ScanActivity.kt
            │   │   ├── PreviewActivity.kt
            │   │   └── LockActivity.kt
            │   ├── fragments/                    # الأجزاء
            │   │   ├── HomeFragment.kt
            │   │   ├── scan/ScanFragment.kt
            │   │   ├── photo/PhotoRecoveryFragment.kt
            │   │   ├── video/VideoRecoveryFragment.kt
            │   │   ├── file/FileRecoveryFragment.kt
            │   │   ├── sms/SmsRecoveryFragment.kt
            │   │   ├── calllog/CallLogRecoveryFragment.kt
            │   │   ├── appdata/AppDataRecoveryFragment.kt
            │   │   ├── backup/BackupFragment.kt
            │   │   ├── recyclebin/RecycleBinFragment.kt
            │   │   ├── filemanager/FileManagerFragment.kt
            │   │   └── settings/SettingsFragment.kt
            │   ├── viewmodel/                    # نماذج العرض
            │   │   ├── MainViewModel.kt
            │   │   ├── ScanViewModel.kt
            │   │   ├── PhotoRecoveryViewModel.kt
            │   │   ├── VideoRecoveryViewModel.kt
            │   │   ├── FileRecoveryViewModel.kt
            │   │   ├── SmsRecoveryViewModel.kt
            │   │   ├── CallLogRecoveryViewModel.kt
            │   │   ├── AppDataRecoveryViewModel.kt
            │   │   ├── BackupViewModel.kt
            │   │   ├── RecycleBinViewModel.kt
            │   │   ├── FileManagerViewModel.kt
            │   │   └── SettingsViewModel.kt
            │   ├── widgets/                      # عناصر مخصصة
            │   │   ├── ScanProgressView.kt
            │   │   └── StorageInfoView.kt
            │   ├── animations/
            │   │   └── AnimationUtils.kt
            │   └── theme/
            │       ├── Color.kt
            │       ├── Type.kt
            │       └── Theme.kt
            │
            ├── service/                          # ⚙️ خدمات الخلفية
            │   ├── ScanService.kt               # خدمة المسح الأمامية
            │   ├── BackupService.kt             # خدمة النسخ الاحتياطي
            │   └── RecycleBinMonitorService.kt  # خدمة مراقبة المحذوفات
            │
            ├── utils/                            # 🛠️ أدوات مساعدة
            │   ├── ai/
            │   │   ├── AIRecoveryAssistant.kt    # مساعد الاستعادة الذكي
            │   │   └── StorageAnalyzer.kt       # محلل التخزين
            │   ├── backup/
            │   │   ├── BackupManager.kt          # مدير النسخ الاحتياطي
            │   │   └── CloudBackupProvider.kt    # مزود النسخ السحابي
            │   ├── crypto/
            │   │   └── CryptoManager.kt          # مدير التشفير والأمان
            │   ├── recyclebin/
            │   │   ├── SmartRecycleBin.kt        # سلة المحذوفات الذكية
            │   │   └── FileMonitorService.kt     # خدمة مراقبة الملفات
            │   ├── storage/
            │   │   └── StorageUtils.kt           # أدوات التخزين
            │   └── permission/
            │       └── PermissionManager.kt      # مدير الصلاحيات
            │
            └── manager/
                └── FileManager.kt                # مدير الملفات
```

---

## 📋 المتطلبات

### متطلبات التطوير

| المتطلب | الإصدار المطلوب |
|---------|----------------|
| Android Studio | Hedgehog (2023.1.1) أو أحدث |
| JDK | 17 |
| Kotlin | 1.9.22 |
| Gradle | 8.2.2 |
| Android SDK | compileSdk 34 |
| Min SDK | 29 (Android 10) |
| Target SDK | 34 (Android 14) |
| RAM | 8 GB كحد أدنى (16 GB مستحسن) |
| مساحة القرص | 10 GB متاحة |

### متطلبات وقت التشغيل

| المتطلب | الوصف |
|---------|-------|
| أندرويد 10+ | الحد الأدنى لنظام التشغيل |
| صلاحيات التخزين | للوصول إلى الملفات والوسائط |
| صلاحيات SMS/سجل المكالمات | لاستعادة الرسائل والمكالمات |
| روت (اختياري) | Magisk أو SuperSU للميزات المتقدمة |
| اتصال إنترنت (اختياري) | للنسخ الاحتياطي السحابي |
| بصمة/وجه (اختياري) | لقفل التطبيق بالبيومتريك |

---

## 🔨 طريقة البناء

### 1. استنساخ المشروع

```bash
git clone https://github.com/ultimaterecovery/pro.git
cd UltimateRecoveryPro
```

### 2. فتح المشروع في Android Studio

```
File → Open → اختر مجلد UltimateRecoveryPro
```

### 3. مزامنة Gradle

```
File → Sync Project with Gradle Files
```

أو من سطر الأوامر:

```bash
./gradlew --refresh-dependencies
```

### 4. بناء نسخة Debug

```bash
# من Android Studio
Build → Make Project (Ctrl+F9)

# أو من سطر الأوامر
./gradlew assembleDebug
```

الملف الناتج: `app/build/outputs/apk/debug/app-debug.apk`

### 5. بناء نسخة Release

```bash
# إنشاء ملف توقيع إذا لم يكن موجوداً
keytool -genkey -v -keystore release.keystore -alias ultimaterecovery \
  -keyalg RSA -keysize 2048 -validity 10000

# بناء النسخة النهائية
./gradlew assembleRelease
```

الملف الناتج: `app/build/outputs/apk/release/app-release.apk`

### 6. تثبيت التطبيق

```bash
# تثبيت نسخة Debug
./gradlew installDebug

# أو يدوياً
adb install app-debug.apk
```

### 7. تشغيل الاختبارات

```bash
# اختبارات الوحدة
./gradlew test

# اختبارات الأجهزة
./gradlew connectedAndroidTest

# فحص الكود
./gradlew lint
```

### إعدادات البناء

| الإعداد | Debug | Release |
|---------|-------|---------|
| `isMinifyEnabled` | `false` | `true` |
| `isShrinkResources` | `false` | `true` |
| `isDebuggable` | `true` | `false` |
| `ENABLE_ROOT_FEATURES` | `true` | `true` |
| ProGuard | غير مفعّل | مفعّل (`proguard-android-optimize.txt` + `proguard-rules.pro`) |

---

## 📚 المكتبات المستخدمة

### المكتبات الأساسية

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `androidx.core:core-ktx` | 1.12.0 | امتدادات Kotlin الأساسية |
| `androidx.appcompat:appcompat` | 1.6.1 | توافق الإصدارات السابقة |
| `com.google.android.material:material` | 1.11.0 | مكونات Material Design |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | نظام التخطيط المتقدم |
| `androidx.activity:activity-ktx` | 1.8.2 | امتدادات النشاط |
| `androidx.fragment:fragment-ktx` | 1.6.2 | امتدادات الأجزاء |
| `androidx.preference:preference-ktx` | 1.2.1 | شاشة الإعدادات |
| `androidx.swiperefreshlayout:swiperefreshlayout` | 1.1.0 | تحديث بالسحب |
| `androidx.recyclerview:recyclerview` | 1.3.2 | عرض القوائم المتقدمة |
| `androidx.viewpager2:viewpager2` | 1.0.0 | عرض الشرائح |
| `androidx.cardview:cardview` | 1.0.0 | بطاقات Material |

### Lifecycle & Architecture

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `lifecycle-viewmodel-ktx` | 2.7.0 | نماذج العرض |
| `lifecycle-livedata-ktx` | 2.7.0 | بيانات حية قابلة للملاحظة |
| `lifecycle-runtime-ktx` | 2.7.0 | امتدادات دورة الحياة |
| `lifecycle-process` | 2.7.0 | دورة حياة العملية |

### التنقل

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `navigation-fragment-ktx` | 2.7.6 | التنقل بين الأجزاء |
| `navigation-ui-ktx` | 2.7.6 | واجهة التنقل |

### قاعدة البيانات

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `room-runtime` | 2.6.1 | قاعدة بيانات Room |
| `room-ktx` | 2.6.1 | دعم Coroutines لـ Room |

### البرمجة غير المتزامنة

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `kotlinx-coroutines-core` | 1.7.3 | Coroutines الأساسية |
| `kotlinx-coroutines-android` | 1.7.3 | Coroutines لأندرويد |

### حقن التبعيات

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `hilt-android` | 2.50 | إطار حقن التبعيات |
| `hilt-compiler` | 2.50 | معالج تعليقات Hilt |

### تحميل الصور والفيديو

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `coil` | 2.5.0 | تحميل الصور (Kotlin-first) |
| `glide` | 4.16.0 | تحميل الصور المتقدم |
| `exoplayer` | 2.19.1 | مشغل فيديو |

### المستندات والملفات

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `documentfile` | 1.0.1 | التعامل مع المستندات |
| `pdfbox-android` | 2.0.27.0 | قراءة ملفات PDF |

### النسخ الاحتياطي السحابي

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `google-api-services-drive` | v3-rev20231211-2.0.0 | Google Drive API |
| `dropbox-core-sdk` | 5.4.5 | Dropbox API |

### الأمان والتشفير

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `security-crypto` | 1.1.0-alpha06 | تشفير EncryptedSharedPreferences |
| `biometric` | 1.1.0 | المصادقة البيومترية |

### صلاحيات الروت

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `libsu:core` | 5.0.5 | العمليات الجذرية الأساسية |
| `libsu:io` | 5.0.5 | عمليات الإدخال/الإخراج الجذرية |
| `libsu:nofifier` | 5.0.5 | إشعارات العمليات الجذرية |
| `libsu:service` | 5.0.5 | خدمات العمليات الجذرية |

### المهام في الخلفية

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `work-runtime-ktx` | 2.9.0 | جدولة المهام الدورية |

### واجهة المستخدم المتقدمة

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `lottie` | 6.3.0 | رسوم متحركة متجهية |
| `shimmer` | 0.5.0 | تأثير التحميل اللامع |
| `Android-SpinKit` | 1.4.0 | مؤشرات تقدم متحركة |
| `MPAndroidChart` | v3.1.0 | رسوم بيانية احترافية |
| `fastadapter` | 5.10.2 | محول قوائم سريع |
| `fastadapter-extensions-binding` | 5.10.2 | ربط البيانات مع FastAdapter |

### التخزين والصلاحيات

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `storage` (anggrayudi) | 1.5.5 | إدارة التخزين المتقدمة |
| `permissionx` | 1.7.1 | إدارة الصلاحيات المبسطة |

### أدوات مساعدة

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `gson` | 2.10.1 | تحويل JSON |

### الاختبارات

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `junit` | 4.13.2 | اختبارات الوحدة |
| `mockito-core` | 5.8.0 | كائنات محاكاة |
| `mockito-kotlin` | 5.2.1 | محاكاة Kotlin |
| `kotlinx-coroutines-test` | 1.7.3 | اختبار Coroutines |
| `espresso-core` | 3.5.1 | اختبارات واجهة المستخدم |
| `room-testing` | 2.6.1 | اختبار Room |

---

## 🧩 واجهة برمجة التطبيق

### طبقة العرض (UI Layer)

تعتمد طبقة العرض على نمط **MVVM** مع Data Binding و View Binding:

- **Activities**: نقاط الدخول الرئيسية (`MainActivity`, `ScanActivity`, `LockActivity`, `PreviewActivity`)
- **Fragments**: شاشات التطبيق المختلفة (الرئيسية، المسح، الاستعادة، النسخ، الإعدادات)
- **ViewModels**: إدارة حالة واجهة المستخدم والتنسيق بين البيانات والعرض
- **LiveData/StateFlow**: تدفقات البيانات القابلة للملاحظة لتحديث الواجهة تلقائياً

### طبقة المنطق (Domain Layer)

تحتوي على المحركات الأساسية للتطبيق:

- **ScanEngine**: محرك المسح الرئيسي - ينسق بين جميع أنواع المسح
- **FileRecoveryEngine**: محرك الاستعادة - يدير عملية استعادة الملفات الفعلية
- **AIRecoveryAssistant**: المساعد الذكي - تحليل وتصنيف وإصلاح الملفات
- **BackupManager**: مدير النسخ الاحتياطي - إنشاء واستعادة النسخ
- **SmartRecycleBin**: سلة المحذوفات - اعتراض وحفظ الملفات المحذوفة
- **RootManager**: مدير الروت - فحص ومنح صلاحيات الجذر

### طبقة البيانات (Data Layer)

تتبع نمط **Repository Pattern** مع مصادر بيانات متعددة:

- **Room Database**: 9 جداول مع 9 DAOs لتخزين البيانات المحلية
- **ContentProvider**: قراءة SMS وسجل المكالمات ووسائط التخزين
- **SharedPreferences (Encrypted)**: إعدادات التطبيق المشفرة
- **File System**: قراءة/كتابة الملفات المباشرة
- **Cloud APIs**: Google Drive و Dropbox للنسخ السحابي

### قاعدة البيانات

قاعدة البيانات `UltimateRecoveryDatabase` (الإصدار 1) تحتوي على 9 جداول:

| الجدول | الوصف |
|--------|-------|
| `recovered_files` | الملفات المستعادة |
| `scan_sessions` | جلسات المسح |
| `sms_messages` | رسائل SMS المستعادة |
| `call_logs` | سجلات المكالمات المستعادة |
| `app_data` | بيانات التطبيقات المستعادة |
| `account_data` | بيانات الحسابات |
| `backups` | سجلات النسخ الاحتياطي |
| `recycle_bin_items` | عناصر سلة المحذوفات |
| `recovery_history` | سجل عمليات الاستعادة |

إعدادات قاعدة البيانات:
- `PRAGMA journal_mode=WAL` — أداء أفضل مع الكتابة المتزامنة
- `PRAGMA foreign_keys=ON` — تطبيق القيود المرجعية
- `exportSchema=true` — تصدير مخطط قاعدة البيانات

---

## 🔑 نظام الصلاحيات

### صلاحيات التخزين

| الصلاحية | المستوى | الوصف |
|----------|---------|-------|
| `READ_EXTERNAL_STORAGE` | ≤ SDK 32 | قراءة التخزين الخارجي (مهمل في Android 13+) |
| `WRITE_EXTERNAL_STORAGE` | ≤ SDK 29 | كتابة التخزين الخارجي (مهمل في Android 10+) |
| `MANAGE_EXTERNAL_STORAGE` | جميع | الوصول الكامل لجميع الملفات |
| `READ_MEDIA_IMAGES` | SDK 33+ | قراءة الصور من الوسائط |
| `READ_MEDIA_VIDEO` | SDK 33+ | قراءة الفيديوهات من الوسائط |
| `READ_MEDIA_AUDIO` | SDK 33+ | قراءة الملفات الصوتية من الوسائط |

### صلاحيات الاتصالات

| الصلاحية | الوصف |
|----------|-------|
| `READ_SMS` | قراءة الرسائل النصية للاستعادة |
| `READ_CALL_LOG` | قراءة سجل المكالمات للاستعادة |
| `READ_CONTACTS` | قراءة جهات الاتصال لعرض أسماء المتصلين |
| `READ_PHONE_STATE` | معرفة حالة الهاتف |

### صلاحيات الشبكة

| الصلاحية | الوصف |
|----------|-------|
| `INTERNET` | اتصال الإنترنت للنسخ السحابي |
| `ACCESS_NETWORK_STATE` | فحص حالة الشبكة |

### صلاحيات الخدمات

| الصلاحية | الوصف |
|----------|-------|
| `FOREGROUND_SERVICE` | تشغيل خدمات أمامية (المسح، النسخ) |
| `FOREGROUND_SERVICE_DATA_SYNC` | خدمة مزامنة البيانات الأمامية |
| `WAKE_LOCK` | إبقاء الجهاز مستيقظاً أثناء العمليات الطويلة |
| `RECEIVE_BOOT_COMPLETED` | بدء المراقبة عند تشغيل الجهاز |

### صلاحيات الأمان

| الصلاحية | الوصف |
|----------|-------|
| `USE_BIOMETRIC` | المصادقة البيومترية (بصمة/وجه) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | تجاهل تحسينات البطارية |
| `POST_NOTIFICATIONS` | إرسال إشعارات (Android 13+) |

### ميزات الأجهزة

| الميزة | مطلوب | الوصف |
|--------|-------|-------|
| `android.hardware.touchscreen` | لا | شاشة اللمس |
| `android.hardware.fingerprint` | لا | مستشعر البصمة |

---

## 🔐 نظام الروت

يعتمد نظام الروت على مكتبة **libsu** من topjohnwu (مطوّر Magisk) ويوفر واجهة موحدة لجميع العمليات الجذرية.

### اكتشاف الروت

يستخدم `RootManager` 7 طرق لاكتشاف وجود الروت:

```
┌─────────────────────────────────────────────────┐
│            Root Detection Methods                │
├─────────────────────────────────────────────────┤
│ 1. libsu Shell Check      → Shell.isRoot       │
│ 2. su Binary Check        → /system/bin/su, ... │
│ 3. Superuser App Check    → Magisk, SuperSU, .. │
│ 4. BusyBox Check          → /system/xbin/...    │
│ 5. Magisk Indicators      → /sbin/.magisk, ...  │
│ 6. SuperSU Indicators     → /system/app/SuperSU │
│ 7. System Properties      → ro.debuggable, ...  │
└─────────────────────────────────────────────────┘
```

### حالات الروت

```
RootState (Sealed Class)
├── Unknown        → لم يتم الفحص بعد
├── NotAvailable   → الجهاز غير متجذر
├── Available      → الروت متاح لكن لم يُمنح
├── Granted        → صلاحيات الروت ممنوحة ✓
├── Denied         → المستخدم رفض منح الروت
└── Revoked        → تم سحب صلاحيات الروت
```

### أنواع حلول الروت المدعومة

| النوع | الوصف |
|-------|-------|
| `MAGISK` | Magisk — الحل الأكثر شيوعاً حالياً |
| `SUPERSU` | SuperSU — حل أقدم |
| `UNKNOWN` | روت متكتشف لكن نوعه غير معروف |
| `NONE` | لا يوجد روت |

### عمليات الروت المدعومة

| العملية | الوصف |
|---------|-------|
| `executeCommand()` | تنفيذ أمر جذر والتحقق من النجاح |
| `executeCommandWithOutput()` | تنفيذ أمر والتقاط المخرجات |
| `executeCommandStreaming()` | تنفيذ أمر طويل مع تدفق المخرجات |
| `executeCommands()` | تنفيذ أوامر متعددة بالتتابع |
| `openFileAsRoot()` | فتح ملف عبر صلاحيات الروت |
| `listFilesAsRoot()` | عرض محتويات مجلد محمي |
| `readFileAsRoot()` | قراءة محتوى ملف محمي |
| `copyFileAsRoot()` | نسخ ملف عبر صلاحيات الروت |
| `fileExistsAsRoot()` | فحص وجود ملف محمي |
| `getFilePermissions()` | الحصول على صلاحيات ملف |

### مسارات su المدعومة

```kotlin
SU_BINARY_PATHS = listOf(
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/vendor/bin/su",
    "/system/sd/xbin/su",
    "/system/bin/failsafe/su",
    "/data/local/xbin/su",
    "/data/local/bin/su",
    "/data/local/su",
    "/su/bin/su",
    "/magisk/.core/bin/su",
    "/debugfs/su"
)
```

---

## 🔬 نظام الفحص

يعتمد نظام الفحص على **ScanEngine** كمنسق رئيسي بين أربعة ماسحات متخصصة. يدعم النظام الإيقاف المؤقت والاستئناف والإلغاء، مع تتبع التقدم في الوقت الفعلي عبر `StateFlow`.

### مخطط تدفق الفحص

```
                  ┌─────────────┐
                  │  المستخدم   │
                  └──────┬──────┘
                         │ يبدأ المسح
                         ▼
              ┌─────────────────────┐
              │     ScanEngine      │
              │   (المنسق الرئيسي)  │
              └──────────┬──────────┘
                         │
         ┌───────────────┼───────────────┬────────────────┐
         ▼               ▼               ▼                ▼
  ┌─────────────┐ ┌─────────────┐ ┌──────────────┐ ┌──────────────┐
  │QuickScanner │ │DeepScanner  │ │Signature     │ │MediaScanner  │
  │             │ │             │ │Scanner       │ │              │
  │نظام الملفات│ │الكتل الخام  │ │التوقيعات     │ │MediaStore    │
  └──────┬──────┘ └──────┬──────┘ └──────┬───────┘ └──────┬───────┘
         │               │               │                │
         └───────────────┴───────────────┴────────────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │   ScanState Flow    │
              │  (تحديثات فورية)    │
              └──────────┬──────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │      واجهة المستخدم  │
              └─────────────────────┘
```

### حالات المسح (ScanState)

```
ScanState (Sealed Class)
├── Idle           → لا يوجد مسح جارٍ
├── Scanning       → مسح نشط
│   ├── progress   → نسبة التقدم (0.0 - 1.0)
│   ├── currentPath → المسار الحالي
│   ├── filesFound → عدد الملفات المكتشفة
│   ├── bytesScanned → البايتات الممسوحة
│   ├── totalBytes  → إجمالي البايتات
│   ├── scanType    → نوع المسح
│   └── elapsedMs   → الوقت المنقضي
├── Paused         → مسح متوقف مؤقتاً
├── Completed      → مسح مكتمل بنجاح
│   ├── results    → قائمة الملفات المكتشفة
│   ├── totalFiles → إجمالي الملفات
│   ├── totalSize  → إجمالي الحجم
│   └── durationMs → مدة المسح
├── Failed         → مسح فاشل
│   ├── error      → رسالة الخطأ
│   └── partialResults → نتائج جزئية
└── Cancelled      → مسح ملغى
```

### أنواع المسح

#### 1. المسح السريع (Quick Scan) ⚡

- **السرعة**: سريع (ثوانٍ إلى دقائق)
- **العمق**: سطحياً
- **يتطلب روت**: لا
- **الآلية**: فحص نظام الملفات عن إدخالات محذوفة
- **الاستخدام**: فحص أولي سريع

```kotlin
scanEngine.startQuickScan(
    paths = listOf("/storage/emulated/0"),
    categories = listOf(FileCategory.PHOTO, FileCategory.VIDEO)
)
```

#### 2. المسح العميق (Deep Scan) 🔄

- **السرعة**: متوسط (5-30 دقيقة)
- **العمق**: عميق
- **يتطلب روت**: نعم
- **الآلية**: قراءة الكتل الخام من التخزين والبحث عن توقيعات الملفات
- **الاستخدام**: عندما لا يجد المسح السريع كفاية

```kotlin
scanEngine.startDeepScan(
    paths = listOf("/storage/emulated/0"),
    categories = listOf(FileCategory.PHOTO, FileCategory.DOCUMENT)
)
```

#### 3. مسح التوقيعات (Signature Scan) 🎯

- **السرعة**: متوسط (10-45 دقيقة)
- **العمق**: متوسط
- **يتطلب روت**: لا
- **الآلية**: البحث عن Magic Numbers في المساحة غير المخصصة
- **الاستخدام**: استعادة ملفات حُذفت إدخالاتها من نظام الملفات

```kotlin
scanEngine.startSignatureScan(
    paths = listOf("/storage/emulated/0")
)
```

#### 4. المسح الخام (Raw Scan) 🐢

- **السرعة**: بطيء جداً
- **العمق**: كامل (بايت ببايت)
- **يتطلب روت**: نعم
- **الآلية**: قراءة قسم كامل بايت ببايت مع البحث عن توقيعات الملفات
- **الاستخدام**: أقصى درجات الاستعادة

```kotlin
scanEngine.startRawScan(
    partitionPath = "/dev/block/bootdevice/by-name/userdata"
)
```

#### 5. مسح الأقسام (Partition Scan) 🐢

- **السرعة**: بطيء جداً (30-120 دقيقة)
- **العمق**: شامل
- **يتطلب روت**: نعم
- **الآلية**: اكتشاف جميع أقسام التخزين ومسحها بالتتابع
- **الاستخدام**: استعادة شاملة لجميع الأقسام

```kotlin
scanEngine.startPartitionScan()
```

### توقيعات الملفات المدعومة

يحتوي `FileSignatures` على **40+ توقيع ملف** للمقارنة:

| الفئة | عدد التوقيعات | الأمثلة |
|-------|--------------|---------|
| صور | 17 | JPEG (`FFD8FF`), PNG (`89504E47`), WEBP (`RIFF+WEBP`), HEIC (`ftyp+heic`), CR2, NEF, ARW... |
| فيديو | 8 | MP4 (`ftyp+mp42`), AVI (`RIFF+AVI`), MKV (`1A45DFA3`), MOV, FLV, 3GP, WMV, MPEG |
| صوت | 5 | MP3 (`FFFB/ID3`), WAV (`RIFF+WAVE`), FLAC (`fLaC`), AAC, OGG |
| مستندات | 7 | PDF (`%PDF`), OLE2 (`D0CF11E0`), DOCX/XLSX/PPTX (`PK`), RTF, TXT |
| أرشيفات | 5 | ZIP (`PK`), RAR (`Rar!`), 7Z (`377ABCAF`), TAR, GZ |
| APK | 1 | APK (`PK` + `AndroidManifest.xml`) |

### عملية الاستعادة

بعد اكتمال المسح، يتم تمرير النتائج إلى `FileRecoveryEngine`:

```
ملف مكتشف → قراءة البيانات → كتابة في موقع الإخراج
                         ↓
              التحقق من السلامة (FileSignatures)
                         ↓
              ┌────────────────────────┐
              │ هل الملف سليم؟         │
              ├── نعم ──→ حفظ النتيجة  │
              ├── جزئياً ──→ محاولة إصلاح │
              │   ├── JPEG → إضافة EOI │
              │   ├── PNG → إضافة IEND │
              │   └── PDF → إضافة %%EOF │
              └── تالف ──→ تسجيل الفشل │
              └────────────────────────┘
```

---

## 🔒 نظام التشفير والحماية

### تشفير الملفات (AES-256-GCM)

يعتمد التشفير على **AES-256-GCM** مع اشتقاق المفتاح عبر **PBKDF2WithHmacSHA256**:

```
تنسيق الملف المشفر:
┌──────────┬──────────┬──────────┬──────────────────────┐
│ Header   │ Salt     │ IV       │ Encrypted Data       │
│ (8 bytes)│ (32 bytes)│ (12 bytes)│ + GCM Auth Tag      │
│ "URPENC01"│ عشوائي   │ عشوائي   │                      │
└──────────┴──────────┴──────────┴──────────────────────┘
```

**معلمات التشفير:**

| المعلمة | القيمة |
|---------|--------|
| خوارزمية التشفير | AES-256-GCM |
| حجم المفتاح | 256 بت |
| طول IV | 12 بايت |
| طول علامة المصادقة | 128 بت |
| خوارزمية الاشتقاق | PBKDF2WithHmacSHA256 |
| عدد التكرارات | 100,000 |
| طول الملح | 32 بايت |

### نظام القفل

يدعم التطبيق 4 أنواع من قفل التطبيق:

| نوع القفل | الآلية |
|-----------|--------|
| PIN | تجزئة SHA-256 مع ملح عشوائي |
| كلمة المرور | تجزئة SHA-256 مع ملح عشوائي |
| بيومتريك | مفتاح محمي في Android Keystore |
| بدون | لا يوجد قفل |

```
تدفق المصادقة البيومترية:
┌──────────┐    ┌──────────────┐    ┌────────────────┐
│ المستخدم │───→│ BiometricPrompt│───→│ Android Keystore│
│          │    │              │    │                │
│ بصمة/وجه │    │ مصادقة ناجحة │    │ فتح المفتاح   │
└──────────┘    └──────────────┘    └────────────────┘
```

### الحذف الآمن (DoD 5220.22-M)

الحذف الآمن يتبع معيار وزارة الدفاع الأمريكية:

```
المرحلة 1: كتابة بيانات عشوائية
المرحلة 2: كتابة 0xFF (مكمل المرحلة 1)
المرحلة 3: كتابة أصفار (0x00)
المرحلة 4: اقتطاع الملف إلى 0 بايت
المرحلة 5: حذف الملف
```

> ⚠️ **ملاحظة**: على الأجهزة الحديثة مع SSD/F2FS/ext4 مع Journaling،
> الحذف الآمن لا يضمن الكتابة الفعلية فوق البيانات على المستوى الفعلي للتخزين.

### Android Keystore

يتم تخزين مفاتيح التشفير في Android Keystore الآمن:

| الاسم | الغرض | حماية |
|-------|-------|-------|
| `urp_biometric_key` | مفتاح المصادقة البيومترية | يتطلب مصادقة بيومترية للاستخدام |
| `urp_app_lock_key` | مفتاح قفل التطبيق | استخدام داخلي |

### قنوات الإشعارات

| القناة | الأولوية | الوصف |
|--------|----------|-------|
| `scan_channel` | منخفضة | تقدم عمليات المسح |
| `backup_channel` | منخفضة | تقدم النسخ الاحتياطي |
| `recycle_bin_channel` | منخفضة | حالة سلة المحذوفات |
| `recovery_channel` | متوسطة | نتائج الاستعادة |

---

## 🗺️ خريطة الطريق

### الإصدار 1.0.0 (الحالي) ✅

- [x] بنية MVVM مع Hilt DI
- [x] نظام المسح المتعدد (Quick/Deep/Signature/Raw/Partition)
- [x] محرك استعادة الملفات مع التحقق من السلامة
- [x] دعم الروت عبر libsu
- [x] قاعدة بيانات Room (9 جداول)
- [x] تشفير AES-256-GCM
- [x] قفل التطبيق (PIN/كلمة مرور/بيومتريك)
- [x] سلة المحذوفات الذكية
- [x] النسخ الاحتياطي محلي وسحابي
- [x] AI Recovery Assistant (Heuristic)
- [x] 40+ توقيع ملف
- [x] دعم اللغة العربية

### الإصدار 1.1.0 (القادم) 🔜

- [ ] دعم استعادة المحادثات (WhatsApp/Telegram)
- [ ] نموذج TensorFlow Lite للتصنيف المتقدم
- [ ] واجهة مستخدم محسنة مع Material You
- [ ] دعم السحب والإفلات في مدير الملفات
- [ ] معاينة الملفات المستعادة قبل الحفظ
- [ ] إشعارات تفاعلية مع أزرار إجراء

### الإصدار 1.2.0 🔮

- [ ] استعادة بيانات WhatsApp (صور، فيديوهات، رسائل)
- [ ] استعادة بيانات Telegram
- [ ] مسح عبر OTA (بدون USB)
- [ ] دعم أجهزة ChromeOS
- [ ] وضع داكن متقدم (AMOLED Black)
- [ ] ويدجت شاشة رئيسية للتخزين

### الإصدار 2.0.0 🚀

- [ ] استعادة عبر الشبكة (Wi-Fi Direct)
- [ ] دعم أجهزة التخزين الخارجية (OTG)
- [ ] محرك إصلاح ملفات متقدم (فيديو تالف)
- [ ] تعلم سلوك المستخدم عبر ML
- [ ] دعم الإضافات (Plugin System)
- [ ] وضع عدم الاتصال بالإنترنت الكامل
- [ ] مزامنة سحابية متعددة الأجهزة

---

## 📄 الترخيص

```
Copyright © 2024 Ultimate Recovery Pro

جميع الحقوق محفوظة. لا يجوز نسخ أو تعديل أو توزيع هذا البرنامج
دون إذن كتابي مسبق من المالك.

هذا البرنامج ملكية خاصة (Proprietary Software).
لا يُسمح بالاستخدام التجاري أو إعادة التوزيع دون ترخيص رسمي.

---------

Third-Party Libraries:

This project uses open-source libraries. See individual library licenses:

- Kotlin (Apache 2.0)
- AndroidX Libraries (Apache 2.0)
- Hilt (Apache 2.0)
- Room (Apache 2.0)
- Coroutines (Apache 2.0)
- libsu (Apache 2.0)
- Coil (Apache 2.0)
- Glide (BSD, MIT)
- ExoPlayer (Apache 2.0)
- Lottie (Apache 2.0)
- MPAndroidChart (Apache 2.0)
- Gson (Apache 2.0)
- PDFBox (Apache 2.0)
- PermissionX (Apache 2.0)
- FastAdapter (Apache 2.0)
- Shimmer (BSD)
- SpinKit (MIT)
- SimpleStorage (Apache 2.0)
```

---

<div align="center">

**صُنع بـ ❤️ لاستعادة بياناتك المفقودة**

[⬆ العودة للأعلى](#-ultimate-recovery-pro)

</div>

</div>
