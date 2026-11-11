# Cloudflare-এ চালু করার গাইড (ধাপে ধাপে)

এই গাইড শেষ করলে তিনটা জিনিস চালু হবে:

| অংশ | কোথায় চলবে | ঠিকানা (উদাহরণ) |
|---|---|---|
| সার্ভার + Admin Dashboard | Cloudflare Workers (ডাটাবেস D1, ফাইল R2) | `https://multi-apps.আপনার-নাম.workers.dev/admin/` |
| পাবলিক ডাউনলোড ওয়েবসাইট | Cloudflare Pages | `https://multiapps-site.pages.dev` |

খরচ: ফ্রি প্ল্যানেই চলবে (দিনে ১ লাখ request, ৫ GB ডাটাবেস, ১০ GB ফাইল স্টোরেজ)। শুধু R2 চালু করার সময় Cloudflare একটা কার্ড চায়, তবে ফ্রি সীমার মধ্যে থাকলে টাকা কাটে না।

> **একটা সীমা:** Cloudflare-এর ফ্রি প্ল্যানে একটা request সর্বোচ্চ ১০০ MB হতে পারে, তাই আপনার APK ১০০ MB-এর ছোট হতে হবে।

---

## ধাপ ১ — কম্পিউটারে Node.js ইনস্টল করুন
1. https://nodejs.org থেকে **LTS** ভার্সন ডাউনলোড করে ইনস্টল করুন (Next → Next → Install)।
2. ইনস্টল হয়েছে কিনা দেখতে **Command Prompt** (Windows) বা **Terminal** (Mac) খুলে লিখুন:
   ```
   node -v
   ```
   `v22...` বা তার বেশি কিছু দেখালে ঠিক আছে।

## ধাপ ২ — প্রজেক্ট ফোল্ডারে যান
1. zip ফাইলটা Extract করুন।
2. Command Prompt-এ `multi-apps\cloudflare` ফোল্ডারে যান। Windows-এ সহজ উপায়: ফোল্ডারটা খুলে উপরের ঠিকানার ঘরে `cmd` লিখে Enter চাপুন।
3. প্রয়োজনীয় টুল (wrangler) ইনস্টল করুন:
   ```
   npm install
   ```

## ধাপ ৩ — Cloudflare-এ লগইন করুন
```
npx wrangler login
```
ব্রাউজার খুলবে, **Allow** চাপুন।

## ধাপ ৪ — R2 চালু করুন (একবারই)
Cloudflare Dashboard → বাম পাশে **R2 Object Storage** → **Purchase R2 / Enable R2** → কার্ড দিন → Free প্ল্যান।

## ধাপ ৫ — ডাটাবেস আর স্টোরেজ বানান
```
npx wrangler d1 create multi-apps-db
```
এটা একটা `database_id` দেখাবে (যেমন `a1b2c3d4-...`)। সেটা কপি করে **`wrangler.toml`** ফাইলে (Notepad দিয়ে খুলুন) এখানে বসান:
```
database_id = "PASTE-YOUR-DATABASE-ID-HERE"
```
তারপর:
```
npx wrangler r2 bucket create multi-apps-files
npm run db:init
```
`db:init` জিজ্ঞেস করলে **y** লিখে Enter চাপুন। এতে ডাটাবেসের টেবিলগুলো তৈরি হবে।

## ধাপ ৬ — সার্ভার চালু (deploy) করুন
```
npm run deploy
```
শেষে একটা ঠিকানা দেখাবে, যেমন `https://multi-apps.আপনার-নাম.workers.dev`। **এটা লিখে রাখুন।**

## ধাপ ৭ — প্রথম admin বানানোর setup token দিন
নিজের মতো একটা লম্বা গোপন লেখা বানান (যেমন `MA-setup-7Kq9-xP2v-88Lm`), তারপর:
```
npx wrangler secret put SETUP_TOKEN
```
জিজ্ঞেস করলে লেখাটা paste করে Enter চাপুন।

## ধাপ ৮ — Admin অ্যাকাউন্ট বানান
1. ব্রাউজারে খুলুন: `https://multi-apps.আপনার-নাম.workers.dev/admin/`
2. **Create the first admin** পেজে setup token, username আর password দিন।
   Password কমপক্ষে ১২ অক্ষরের হতে হবে, আর তাতে বড় হাতের অক্ষর, ছোট হাতের অক্ষর, সংখ্যা আর চিহ্ন থেকে অন্তত তিন ধরনের থাকতে হবে।
3. এরপর লগইন করুন। তখন **Google Authenticator** অ্যাপ দিয়ে two-factor চালু করতে বলবে:
   অ্যাপে **+ → Enter a setup key** চাপুন, পেজে দেখানো key টা দিন, আর অ্যাপের ৬ সংখ্যার কোড পেজে লিখুন।
4. **Recovery codes** গুলো ডাউনলোড করে নিরাপদ জায়গায় রাখুন। ফোন হারালে এগুলো লাগবে।
5. প্রথম admin তৈরি হয়ে গেলে setup token আর কাজ করে না। চাইলে মুছে দিতে পারেন:
   ```
   npx wrangler secret delete SETUP_TOKEN
   ```

## ধাপ ৯ — পাবলিক ওয়েবসাইট (Cloudflare Pages)
1. `multi-apps/public-site/config.js` ফাইলটা Notepad দিয়ে খুলে Worker-এর ঠিকানা বসান:
   ```js
   window.MULTI_APPS_API_BASE = 'https://multi-apps.আপনার-নাম.workers.dev';
   ```
2. Cloudflare Dashboard → **Workers & Pages** → **Create** → **Pages** → **Upload assets**।
3. একটা নাম দিন (যেমন `multiapps-site`), তারপর **`public-site` ফোল্ডারটা** টেনে এনে ছেড়ে দিন → **Deploy**।
4. (ঐচ্ছিক) Worker-এর মূল ঠিকানায় কেউ গেলে ওয়েবসাইটে পাঠাতে চাইলে `wrangler.toml`-এ লিখুন:
   `PUBLIC_SITE_URL = "https://multiapps-site.pages.dev"`, তারপর আবার `npm run deploy` চালান।

## ধাপ ১০ — প্রথম APK আপলোড
Dashboard → **Release Center** → **Show form**। এরপর:
- Version code আর version name দিন। এগুলো APK-এর `build.gradle`-এ যা আছে হুবহু তাই হতে হবে।
- APK ফাইল বাছাই করে **Upload as draft** চাপুন।
- সব ঠিক থাকলে **Publish** চাপুন।

এরপর ওয়েবসাইটে **Download APK** বাটন চালু হয়ে যাবে। এর জন্য release-টা stable, Live আর 100% rollout-এ থাকতে হবে।

---

## পরে দরকারি কাজ

**কোড বদলানোর পর আবার চালু করা:** `npm run deploy`

**লাইভ error দেখা:** `npm run logs`

**নিজের ডোমেইন লাগাতে চাইলে:** Workers & Pages → `multi-apps` → Settings → Domains & Routes → Add → Custom domain।

**two-factor-এর ফোন আর recovery code দুটোই হারালে** (নিচের `owner`-এর জায়গায় আপনার username দিন):
```
npx wrangler d1 execute multi-apps-db --remote --command "UPDATE admins SET totp_enabled=0, totp_secret=NULL, totp_last_step=0, recovery_codes='[]' WHERE username='owner'; DELETE FROM sessions;"
```
এরপর লগইন করলে আবার নতুন করে two-factor সেট করতে বলবে।

**Password ভুলে গেলে:** admin মুছে আবার প্রথম admin বানান (ধাপ ৭–৮)। APK, release বা অন্য কোনো ডাটা মুছবে না:
```
npx wrangler d1 execute multi-apps-db --remote --command "DELETE FROM sessions; DELETE FROM admins WHERE username='owner';"
```

**ভুল password দিয়ে অ্যাকাউন্ট লক হয়ে গেলে:** ১৫ মিনিট অপেক্ষা করুন, অথবা:
```
npx wrangler d1 execute multi-apps-db --remote --command "UPDATE admins SET failed_attempts=0, locked_until=0;"
```

**ডাটাবেসের ব্যাকআপ:** `npx wrangler d1 export multi-apps-db --remote --output backup.sql`
