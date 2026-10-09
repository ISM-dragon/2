# تقرير التدقيق الأمني

**تاريخ التدقيق:** 2026-10-05

**النطاق:** نسخة المستودع الحالية، إعدادات Gradle وManifest، تخزين الإعدادات، Gemini وGmail، السجلات، النسخ الاحتياطي، `FileProvider`، الشبكة وقاعدة البيانات. لم تُدرج أي قيم سرية في هذا التقرير.

## الخلاصة

عُثر على نقاط تعرض بيانات أو مفاتيح، إضافةً إلى قصور في معالجة أخطاء التشفير ومسارات مرفقات البريد. عولجت في الشيفرة: لم تعد قيم `.env` تُحقن عشوائياً في `BuildConfig`، أُوقف Android Auto Backup، ضُيّق نطاق `FileProvider`، وأُصلح سلوك `CryptoManager` عند غياب مفتاح Keystore أو فساد ciphertext. كما أزيلت رسائل الاستثناء من السجلات، وحُصّنت مسارات ملفات PDF وترويسات MIME، وأضيفت اختبارات لهذه الضوابط.

لم يُعثر على مفتاح Gemini أو Gmail فعلي. فحص الأنماط في سجل Git المتاح طابق fixtures اختبارية اصطناعية فقط؛ استُبدلت في شجرة العمل الحالية بقيم اختبار لا تشبه credentials، وأصبح فحص الشجرة الحالية نظيفاً. ملف `.env` غير موجود في checkout ومُدرج في `.gitignore`؛ الملف المتعقّب الوحيد هو `.env.example`، وهو الآن توثيقي فقط.

## النتائج والإصلاحات

### SEC-01 — تسريب مفاتيح محتمل عبر BuildConfig — مرتفع — مُعالج

**المشاهدة:** كان Secrets Gradle Plugin يقرأ `.env` ويولّد حقول `BuildConfig`، وكان `ConfigRepository` يقرأ `GEMINI_API_KEY` منها ويزرعه في قاعدة البيانات عند أول تشغيل. أي مفتاح حقيقي أُضيف إلى `.env` كان سيصبح قابلاً للاستخراج من APK، كما أن مفاتيح Gemini في تطبيق عميل لا يمكن اعتبارها أسراراً خادمية.

**الإصلاح:** أزيل Secrets Gradle Plugin من إعدادات البناء. لم يعد `ConfigRepository` يقرأ Gemini من `BuildConfig`؛ تبدأ الخانات الست فارغة ومعطلة، وتُحفظ مفاتيح المستخدم التي يُدخلها من الإعدادات مشفّرة. الحقل الوحيد المسموح به في `BuildConfig` هو `GOOGLE_OAUTH_CLIENT_ID`، وهو معرّف OAuth عام وليس client secret. يُمرّر كخاصية Gradle أو متغير بيئة، ولا تُحمّل قيم `.env` تلقائياً إلى التطبيق. عُدّل `.env.example` لتوضيح ذلك.

**ملاحظة تشغيلية:** مفاتيح Gemini التي يضعها المستخدم في التطبيق تبقى قابلة للوصول من ذاكرة التطبيق أثناء الاستخدام، أو من جهاز مخترق/مُجذّر. إذا كان المطلوب حماية مفتاح خدمة تملكه الشركة، فالحل هو وسيط خادمي مع قيود استخدام، لا تضمينه أو إدخاله في تطبيق الهاتف. إذا سبق بناء APK بمفتاح حقيقي عبر الإعداد القديم، فيجب إبطال ذلك المفتاح وتدويره؛ لا يمكن لهذا التغيير إزالة سر من APK قديم أو نسخة سبق توزيعها.

### SEC-02 — Android Auto Backup يضم قاعدة البيانات والملفات — مرتفع — مُعالج

**المشاهدة:** كان `allowBackup=true` مع تضمين قاعدة Room كاملة ومجلد ملفات العروض. تحتوي قاعدة البيانات على بيانات مالية وعقارية، إضافةً إلى نسخ مشفّرة من Gemini/Gmail credentials. نسخ ciphertext لا يجعل النسخة الاحتياطية قابلة للاستعادة الآمنة على جهاز آخر لأن مفتاح Android Keystore مرتبط بالجهاز.

**الإصلاح:** ضُبط `android:allowBackup="false"`، وعُدّلت قواعد النسخ السحابي ونقل الجهاز لاستبعاد قواعد البيانات والملفات والتفضيلات والجذر كدفاع إضافي. وظيفة النسخ اليدوي الحالية تظل منفصلة وتكتب JSON داخل مجلد التطبيق الخاص؛ ولا تصدّر مفاتيح API أو رموز Gmail.

### SEC-03 — نطاق `FileProvider` أوسع من اللازم — متوسط — مُعالج

**المشاهدة:** كان `file_paths.xml` يتيح مشاركة كل ما تحت `filesDir`، بما في ذلك ملفات النسخ اليدوي، رغم أن التطبيق يحتاج مشاركة PDF العرض فقط.

**الإصلاح:** اقتصر المسار المسموح على `files/offers/`. بقي الـProvider غير مُصدّر (`exported=false`) مع منح قراءة URI عند المشاركة فقط.

### SEC-04 — إنشاء مفتاح جديد أثناء فك التشفير والتعامل مع الفساد — متوسط — مُعالج

**المشاهدة:** كان `getSecretKey()` ينشئ alias جديداً إذا لم يجده، حتى عند محاولة فك بيانات قديمة. كما أعاد استخدام كائن `KeyStore` سبق تحميله قبل إنشاء المفتاح. وكان `decrypt()` يلتقط `Throwable` كاملاً ويحوّل كل الحالات إلى سلسلة فارغة، ما أخفى أخطاء Keystore وأخطاء التنفيذ معاً.

**الإصلاح:** صار إنشاء مفتاح AES-256 في Android Keystore مقتصراً على عمليات التشفير. بعد إنشاء alias يُفتح Keystore من جديد لقراءة المفتاح. فك التشفير لا ينشئ مفتاحاً ولا يدوّر مفتاحاً عند ciphertext فاسد أو مفتاح مفقود؛ يعيد نتيجة فشل داخلية واضحة (`null`) ويظل واجهته القديمة تفشل مغلقة كسلسلة فارغة للتوافق. تُلتقط الاستثناءات (`Exception`) الخاصة بعملية فك التشفير فقط، ولا تُلتقط `Throwable` ولا تُسجّل تفاصيل الاستثناء. بقي تنسيق ciphertext الحالي (IV ثم ciphertext/tag بترميز Base64) واسم alias كما هما للتوافق مع البيانات القائمة.

**الأثر المتوقع:** إذا حُذف مفتاح Keystore أو أُبطل، لا يمكن فك القيم المشفّرة بالمفتاح السابق؛ يجب إعادة إدخال مفتاح Gemini أو إعادة تفويض Gmail. هذا أفضل من إنشاء مفتاح بديل بصمت وإظهار البيانات القديمة كأنها فارغة. التشفير الجديد ينشئ alias عند الحاجة.

### SEC-05 — رسائل استثناء قد تحتوي بيانات طلب — منخفض — مُعالج

**المشاهدة:** كان تهيئة `CryptoManager` تسجّل نص الاستثناء، وكان `PropertySourceManager` يسجّل رسالة استثناء المصدر كاملة. كما كان مسار اختبار Gemini يحفظ نص الاستثناء دون إزالة المفتاح.

**الإصلاح:** أزيل تسجيل تفاصيل الاستثناء من المسارين؛ سجل المصدر يقتصر على نوع الاستثناء. وتُحجب قيمة Gemini API key من رسائل أخطاء الطلب والاختبار قبل إرجاعها أو حفظها. لا تستخدم مسارات Gemini/Gmail أي HTTP logging interceptor.

### SEC-06 — مسارات مرفقات وترويسات Gmail غير موثوقة — متوسط — مُعالج

**المشاهدة:** كان استيراد النسخة اليدوية يقبل `pdfPath` كما ورد في JSON، ثم يستطيع مسار الإرسال قراءة الملف مباشرةً وإرفاقه؛ تقييد `FileProvider` وحده لا يحمي مسار Gmail API المباشر. كذلك كان اسم العرض وموضوع البريد يُركّبان في MIME headers من دون إزالة CR/LF.

**الإصلاح:** أضيف `OfferPdfStorage` لتوليد أسماء ملفات لا تعتمد على مسار ID المستورد، ولحلّ PDF موجود فقط إذا كان ملفاً مباشراً داخل `files/offers/` بعد canonicalization. يُطبّق الفحص عند الاستعادة، والتحقق قبل الإرسال، وداخل GmailService نفسه. تُزال محارف التحكم من قيم MIME header وتُقتبس أسماء العرض واسم المرفق.

### SEC-07 — مزوّد App Check التجريبي ضمن اعتماديات Release — منخفض — مُعالج

**المشاهدة:** كان اعتماد Firebase App Check debug مضافاً إلى `implementation`، وبالتالي يدخل شجرة اعتماديات Release رغم عدم وجود تهيئة App Check في التطبيق.

**الإصلاح:** نُقل الاعتماد إلى `debugImplementation` ليبقى محصوراً ببناء التطوير. لم يُعثر على debug token فعلي في المستودع.

## مراجعة المجالات المطلوبة

| المجال | النتيجة |
|---|---|
| API keys / Gemini | لم يُعثر على مفتاح فعلي في المصدر الحالي. استدعاءات Gemini مباشرة من التطبيق إلى Google عبر HTTPS، والمفتاح يُمرر في header لا في URL. التخزين المحلي للمفاتيح مشفّر، لكن العميل ليس موضعاً آمناً لمفتاح خدمة مركزي. |
| Gmail credentials | `ConfigRepository` يشفّر access/refresh tokens قبل حفظها في Room. إرسال البريد وتجديد الرمز عبر HTTPS؛ رمز الوصول في Authorization header ورمز التجديد في POST body. لم يُعثر على OAuth client secret. `GOOGLE_OAUTH_CLIENT_ID` فقط معرّف عام. أزيلت CR/LF من MIME headers، وقُيّدت ملفات المرفقات بمجلد العروض الداخلي. |
| `.env` / BuildConfig / توقيع التطبيق | لا يوجد `.env` فعلي في checkout، وهو مستثنى من Git. أزيل التحميل التلقائي لـ`.env` إلى BuildConfig؛ يُولّد الحقل العام المحدد أعلاه وحده. إعداد توقيع Release يأخذ المسار وكلمات المرور من متغيرات البيئة؛ القيم الثابتة تخص debug signing الافتراضي فقط. لا يوجد ملف `google-services.json` أو keystore رفع متعقّب في المستودع. |
| السجلات | لا توجد طباعة مباشرة لمفاتيح Gemini أو Gmail tokens. أزيلت رسائل الاستثناء المحتملة التي قد تحتوي بيانات طلب. |
| التشفير وCryptoManager | AES-GCM مع IV عشوائي بطول 12 بايت ووسم مصادقة 128-bit. Android Keystore هو مصدر المفاتيح في Android؛ مصدر الذاكرة المؤقتة خاص بتشغيل اختبارات JVM. فساد Base64 أو فشل المصادقة/المفتاح يفشل مغلقاً. |
| Android backup | Auto Backup ونقل بيانات الجهاز معطلان، والقواعد تستبعد البيانات الحساسة كذلك. النسخ اليدوي يحتوي بيانات عقارية ومالية، لكنه يبقى في التخزين الداخلي ولا يضم إعدادات الأسرار. |
| الأنشطة والخدمات المصدّرة | من Manifest المشروع، `MainActivity` هي المكوّن الوحيد المصدّر، وتصديرها مطلوب للـLauncher. لا توجد خدمات أو receivers للمشروع. `FileProvider` غير مصدّر. |
| FileProvider | محصور في PDFs ضمن `offers/` مع منح URI مؤقتة للقراءة. كما يُرفض مسار مرفق مستورد أو غير موجود خارج المجلد حتى في إرسال Gmail API المباشر. |
| أمن الشبكة / HTTPS | جميع عناوين API المستخدمة Gemini وGmail وOAuth هي HTTPS. ضُبط `usesCleartextTraffic=false` صراحةً (بدلاً من الاعتماد على افتراض target SDK 36). لا توجد Network Security Config أو TrustManager أو hostname-verifier مخصصة تخفف التحقق الافتراضي. |
| قاعدة البيانات | قاعدة Room في مساحة التطبيق الداخلية ولا يوجد ContentProvider يصدّرها. رموز الاعتماد مشفّرة على مستوى الحقول، واستيراد النسخة لا يثق بمسارات PDFs. بقية بيانات العقارات والمالية ليست مشفّرة بقاعدة SQLCipher؛ تعتمد على عزل Android وتشفير الجهاز. لذلك يبقى الوصول عبر جهاز مُجذّر/مخترق خارج حدود هذا الضمان. |

## المخاطر المتبقية / خارج نطاق الإصلاح

- `metadata.json` يصف Gemini بأنه `SERVER_SIDE`، لكن هذا checkout لا يحتوي خادماً؛ `GeminiManager` ينفذ الطلب مباشرةً من Android إلى Google. لا تعتمد على وصف metadata كضمان لإخفاء مفتاح خدمة. يلزم إضافة وسيط خادمي فعلي أو تصحيح metadata وفق متطلبات المنصة قبل اعتبار الاتصال server-side.
- قاعدة Room نفسها ليست مشفّرة بـSQLCipher؛ البيانات غير السرية تبقى ضمن sandbox التطبيق وتشفير الجهاز، بينما credentials فقط تُشفّر على مستوى الحقل.
- لا يمكن سحب مفاتيح أو بيانات سبق تضمينها في APKات/نسخ Android الاحتياطية المنشورة؛ راجعها وأبطل أي مفتاح خدمة قديم عند اللزوم.

## الاختبارات والتحقق

أضيفت الاختبارات التالية:

- `CryptoManagerHardeningTest`: IV مختلف لكل تشفير، round-trip، ciphertext مشوّه/مبتور/معدّل، غياب المفتاح من دون تدوير ضمن فك التشفير، إعادة قراءة Keystore بعد إنشاء المفتاح، احتواء أخطاء المزود، حجب مفتاح Gemini، ومنع CR/LF في MIME headers.
- `OfferPdfStorageSecurityTest`: أسماء ملفات آمنة مع IDs غير موثوقة، ورفض المسارات خارج `offers/` أو مسارات traversal.
- `ConfigRepositorySecurityTest`: خانات Gemini الابتدائية فارغة، عدم وجود حقل `GEMINI_API_KEY` في BuildConfig، وتشفير المفتاح قبل DAO وفكّه عبر repository.
- `AndroidSecuritySurfaceTest`: تعطيل النسخ الاحتياطي، رفض cleartext وفق إعداد التطبيق، تحقق من تصدير Launcher وحده من مكونات التطبيق، ونطاق `FileProvider`.
- حُدّثت fixtures الموجودة كي تستخدم قيماً اصطناعية واضحة لا تشبه credentials حقيقية.

**تعذر تشغيل الاختبارات في هذه البيئة:** لا يتوفر Java أو Gradle أو سكربتات/ملفات Gradle Wrapper في checkout. أُجري بدلاً من ذلك فحص `git diff --check` وفحص XML/المراجع الساكنة؛ يجب تشغيل `testDebugUnitTest` و`assembleDebug` في بيئة Android/Gradle قبل الدمج.

---

# جولة تدقيق ثانية: حدود استيراد العقارات والاعتمادات

**تاريخ التدقيق:** 2026-10-08

**النطاق:** مسار استيراد صفحات العقارات من الروابط (`domain/propertyurl`، `data/urlintelligence`، وحدة `urlintelligence`)، وحدود الاعتمادات (ترويسات الطلب، معلمات الرابط، التخزين، السجلات). لم تُمَس ميزات المالية أو CRM أو الإثراء أو Deal Room.

## النتائج والإصلاحات

### SEC-08 — روابط الصور المستخرجة من مستند غير موثوق تُجلب دون فحص الشبكة — مرتفع — مُعالج

**المشاهدة:** `ValueGuards.imageUrl` (التطبيق) و`PropertyNormalizer.isHttpUrl` (وحدة `urlintelligence`) كانا يقبلان أي رابط `http(s)` — بما في ذلك `https://192.168.1.1/…` و`https://169.254.169.254/…` و`https://[::1]/…` و`https://gateway.internal/…` — ثم تُخزَّن هذه الروابط في `PropertyEntity.primaryImageUrl` و`PropertyImageEntity.imageUrl` وتُجلب لاحقاً مباشرةً عبر `AsyncImage`/Coil في `PropertyCard` و`PropertyDetailScreen`. أي صفحة listing معادية تستطيع بذلك توجيه التطبيق إلى أجهزة الشبكة المحلية للمستخدم، وهو نفس المسار الذي يرفضه جلب المستند نفسه. الحواجز الموجودة لم تكن كافية: `usesCleartextTraffic=false` يمنع `http://` فقط، ولا يمنع `https://` على عنوان خاص.

**الإصلاح:** صار فحص روابط الصور مطابقاً لفحص رابط الـlisting: مخطط `http(s)` مطلق فقط، بدون userinfo، بدون IP literal، وباسم مضيف عام (يُرفض loopback/link-local/RFC1918/CGNAT/metadata). أُضيف أيضاً رفض مقاطع `..` في المسار، ورفض الصيغة النسبية `//host/…`.

### SEC-09 — فحص المضيف الخاص لا يغطي نطاقات الأسماء غير القابلة للتوجيه — متوسط — مُعالج

**المشاهدة:** `UrlHosts.isPrivateNetwork` كان يفحص نطاقات IPv4 جزئياً ولا يعرف `*.internal` ولا `*.home.arpa` ولا `*.arpa` ولا `metadata[.google.internal]`، كما كان يفوت `224/4` و`240/4` و`192.0.0/24` و`192.0.2/24` و`198.18/15` و`198.51.100/24` و`203.0.113/24`. النتيجة أن `PropertyUrlValidator` كان يعيد `Valid` لرابط مثل `https://gateway.internal/…`. كان `DEFAULT_REDIRECT_GUARD` و`intelligence/source/PropertyUrlResolver` يعوّضان ذلك بقوائم مكرّرة خاصّة بهما، ما يعني أن أي مستخدم آخر للدالة يبقى مكشوفاً. وكان هناك أيضاً إنذار كاذب: أي مضيف يبدأ بـ`fc` (مثل `fc.example.com`) كان يُعتبر خاصاً.

**الإصلاح:** وُحّدت القوائم داخل `isPrivateNetwork` (أسماء غير قابلة للتوجيه + نطاقات IPv4 المحجوزة كاملة + بادئات IPv6 غير العامة)، وأُصلح الإنذار الكاذب باشتراط `:` قبل معاملة المضيف كـIPv6. بقي `PublicOnlyDns` هو الفحص الحاسم للعناوين بعد التحليل.

### SEC-10 — سطر `User-agent:` فارغ في robots.txt يلغي قاعدة `*` — متوسط — مُعالج

**المشاهدة:** `RobotsTxt.selectGroup` كان يطابق الرمز عبر `loweredAgent.contains(token)` دون استثناء الرمز الفارغ، و`RobotsTxt.parse` يضيف قيمة `User-agent:` الفارغة كما هي. ملف robots.txt مقطوع أو مشوّه مثل `User-agent:` ثم `Allow: /` كان يُختار لكل crawler ويحجب مجموعة `User-agent: *` بالكامل، فيُسمح بجلب مسارات منعها الموقع صراحةً.

**الإصلاح:** الرمز الفارغ لا يطابق أي crawler، فتسقط المجموعة إلى `*`. لم يتغيّر تحليل الملف ولا أسبقية المجموعات المسماة.

### SEC-11 — بوابة robots تفشل مفتوحة عند تعذّر التقييم — متوسط — مُعالج

**المشاهدة:** `RobotsTxtFetchPolicy` كان افتراضيه `UnavailableBehavior.ALLOW`، والإنتاج يستخدم `DefaultFetchPolicies.robotsAware(...)` بهذا الافتراض. أي 5xx أو timeout أو حلقة redirect في `/robots.txt` كانت تعني «مسموح»، بينما الوحدة الشقيقة `RobotsPolicy` تفشل مغلقة والوثائق تصف البوابة بأنها fail-closed.

**الإصلاح:** الافتراض صار `DENY` (مع بقاء `404/410` = «لا قواعد منشورة» = مسموح، وبقاء `ALLOW` خياراً صريحاً لمن فحص المصدر خارجياً). **تغيير سلوك مقصود:** عند انقطاع `/robots.txt` يفشل الاستيراد بدلاً من المتابعة.

### SEC-12 — قيم الاعتماد في الرابط تبقى في الرابط القانوني وتُحفظ كنص صريح — مرتفع — مُعالج

**المشاهدة:** `PropertyUrlNormalizer.canonicalizeQuery` كان يسجّل أسماء المعلمات الحساسة في `sensitiveParameters` لكنه **يُبقيها بقيمها** في الرابط القانوني. وهذا الرابط هو ما يُجلب (`HttpRequest(url = resolved.url.normalized)`)، وما يُبنى منه `identitySeed`، ومنه `ResolvedPropertyUrl.idempotencyKey` الذي يُكتب نصاً صريحاً في `JobCodec` داخل `<filesDir>/property-url-intelligence/jobs/*.json`. كان هذا يخالف ثلاثة مواضع أخرى: رسالة `PropertyUrlValidator` («they are stripped before fetch»)، والاختبار `imported jobs do not retain credential-like URL query values`، ووحدة `urlintelligence` التي تحذف هذه المعلمات فعلاً، و`OkHttpHttpFetcher`/`OkHttpPropertyTransport` اللذان يرفضان الرابط أصلاً إذا حملها.

**الإصلاح:** تُحذف المعلمات الحساسة من الرابط القانوني (ويُسجّل الاسم فقط للتشخيص)، وصار `idempotencyKey` المشتق من الرابط بصمة SHA-256 بدل النص الصريح كدفاع إضافي. لم تتأثر معرّفات الـlisting (`zpid`, `listingId`, `pid`) لأنها ليست أسماءً حساسة.

### SEC-13 — مصدر عام قد يُرسل ترويسات اعتماد — منخفض — مُعالج

**المشاهدة:** `PropertyUrlIntelligence` كان يمرّر `credentialProvider.credentialsFor(sourceId)` إلى `AdapterContext` لأي مصدر، دون ربط ذلك بـ`capabilities.requiresCredentials`. الإنتاج يستخدم `CredentialProvider.NONE` فلا أثر مباشر اليوم، لكن أي إدخال اعتمادات تحت معرّف `zillow` أو `generic_web` (وهو المصدر الذي يجلب روابط المستخدم التعسفية) كان سيُرفق `Authorization`/`Cookie`/`X-Api-Key` بطلب صفحة عامة، و`SourceCredentials.extraHeaders` غير مُرشَّح.

**الإصلاح:** لا تُرفق اعتمادات إلا لمصدر يعلن `requiresCredentials = true` (حالياً `mls_feed` و`county_records`). بقيت آلية الإرفاق كما هي لمن يعلنها.

## مجالات فُحصت ولم تحتج تعديلاً

| المجال | النتيجة |
|---|---|
| Android Keystore | `keyForEncryption` يقرأ ثم ينشئ ثم يعيد القراءة؛ مسار فك التشفير لا ينشئ ولا يدوّر مفتاحاً. AES-256-GCM، IV عشوائي 12 بايت مع `setRandomizedEncryptionRequired(true)`، وسم 128-bit. المصدر المؤقت في الذاكرة يُختار فقط عندما لا يكون التشغيل على Dalvik/Android. |
| معالجة ciphertext | `decryptOrNull` يفشل مغلقاً (`null`) على Base64 تالف أو طول أقصر من IV+وسم أو فشل مصادقة أو غياب مفتاح، ويلتقط `Exception` دون تسجيل الـciphertext أو الرسالة؛ و`decrypt` يرفع `CryptoDecryptionException` بدل إظهار قيمة فارغة صالحة. |
| حجب الرموز في السجلات | `Redaction` تحجب bearer/JWT/مفاتيح Google/تعيينات `key=value`/بريد/هواتف، و`isSensitiveHeader` تغطي `authorization`/`cookie`/`set-cookie`/`proxy-authorization` وأي اسم يحوي `token`/`secret`/`api-key`. `JobCodec` يمرّر `rawInput` و`normalizedUrl` و`sourceUrl` عبر `Redaction.url`. لا `println`/`Log` في مسار الاستيراد. |
| تقييد الشبكة الخاصة | `PublicOnlyDns` يرفض أي إجابة ليست عامة بالكامل (يشمل IPv4-mapped IPv6، ULA، link-local، multicast، benchmarking، documentation) ويُستخدم نفسه للاتصال فلا توجد نافذة إعادة تحليل. |
| تقييد الـredirects | `followRedirects=false` في النقلين، والـredirect يُتحقق منه يدوياً عبر `isAllowedPublicUrl` **و**`redirectGuard` معاً (لا يكفي تجاوز أحدهما)، وتُجرَّد كل الترويسات المخصّصة عند تغيّر الـorigin. |
| سياسة robots | تُجلب عبر نفس النقل بدون اعتمادات، وتُخزَّن مؤقتاً 6 ساعات لكل origin، ولا تُخزَّن عند الفشل. |
| المدخلات الضخمة/المشوّهة | حدود طول الرابط 2048، وحدود جسم الاستجابة مفروضة في النقلين ثم يُعاد فحصها في `ResponseGuard`، و`RobotsRules`/`RobotsTxt` تتجاهلان الأسطر المشوّهة، و`JobCodec` يتدهور إلى «حقل غير موجود» بدل فشل الاستعادة. |
| ترويسات الاعتماد في الاستيراد العام | `SourceFetchRequest.FORBIDDEN_HEADERS` + `Redaction.isSensitiveHeader` في `OkHttpPropertyTransport`، وتجريد كل الترويسات عند تغيّر الـorigin في `OkHttpHttpFetcher`، وبوابة `requiresCredentials` الجديدة (SEC-13). |
| افتراضات النسخ الاحتياطي | `allowBackup="false"` مع `data_extraction_rules`/`backup_rules` تستثني database/file/sharedpref/root/external. النسخ اليدوي يصدر العقارات والمالية والعروض وقالب العروض فقط، ولا يصدّر مفاتيح Gemini ولا رموز Gmail، ويعيد التحقق من مسار PDF عبر `OfferPdfStorage.resolveExistingPdf`. |

## الاختبارات المضافة

- `app/src/test/java/com/example/domain/propertyurl/IngestionBoundarySecurityTest.kt`: رفض روابط الصور الخاصة/المحلية/المحمّلة بالاعتمادات، وفحص الأسماء والنطاقات غير القابلة للتوجيه، ورفض `*.internal` قبل الجلب، وحذف قيم الاعتماد من الرابط القانوني، و`User-agent` الفارغ، وفشل بوابة robots مغلقاً (و`404` مسموح، و`ALLOW` الصريح ما زال يعمل)، وعدم إرسال اعتمادات لصفحة عامة مع استمرار إرسالها لمصدر يعلنها، وعدم تسرّب القيم إلى سجل الوظائف.
- `urlintelligence/src/test/kotlin/com/example/urlintelligence/IngestionImageGuardTest.kt`: إسقاط الصور ذات المضيف الخاص/المحلي في `PropertyNormalizer`، ومنع ترقية صورة primary معادية، وإبقاء الصور العامة.

**تعذّر تشغيل الاختبارات في هذه البيئة:** لا يوجد Java ولا Gradle ولا مترجم Kotlin في الـsandbox، ولا يمكن جلبها: `repo1.maven.org` و`dl.google.com` و`services.gradle.org` و`plugins.gradle.org` وأصول إصدارات GitHub كلها غير قابلة للوصول (الاتصال مسموح فقط بـgithub.com وcodeload وapi.github.com وPyPI). لذلك لم يُنفَّذ `testDebugUnitTest` ولا `:urlintelligence:test`. ما نُفِّذ فعلياً هنا: `git diff --check` (نظيف)، وفحص توازن الأقواس/السلاسل/التعليقات على كل ملف مُعدَّل (متوازن)، وتحقّق يدوي من وجود كل معرّف مستخدم ورؤيته (`ACCEPT`، `UrlParser` internal، `isBlockedHost` internal، `SourceCredentials`، `PropertySourceDefinition`، `JobCodec.encode`). يجب تشغيل `gradle :app:testDebugUnitTest :urlintelligence:test` قبل الدمج.

---

## Round 3 validation (2026-10)

Full details, threat model, ranked findings, fixes and open release blockers are in
[`docs/SECURITY_REVIEW_2026-10.md`](docs/SECURITY_REVIEW_2026-10.md). Summary of changes to the claims above:

- **SEC-07 (partial):** the release build still contained the App Check reCAPTCHA provider and an unused `firebase-ai` SDK. Both were removed from the build, along with the unused logging interceptor.
- **SEC-08 (partial):** the ingestion guard held, but backup restore bypassed it, and image loading followed redirects and resolved any hostname. Restore now applies the guard, and the Coil loader refuses redirects and non-public DNS answers.
- **SEC-12 (partial):** the app's stored URLs were redacted, but the `urlintelligence` module's job store kept raw credentials. Fixed in the module.
- **New:** offers were addressed to a hard-coded placeholder recipient. Sends to that address are now blocked, pending a recipient-entry UI (release blocker).
- **Test status:** at base, 13 of 228 `urlintelligence` tests failed and CI did not run them. Module status after this round: 234 of 235 pass (one identity-key expectation remains open). Android/Robolectric tests were not run in this environment.
