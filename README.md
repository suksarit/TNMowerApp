# 🌿 TN Mower

แอป Android สำหรับควบคุมและรับข้อมูลจากระบบ **TN Mower** ผ่าน Bluetooth
ใช้ร่วมกับ Arduino / ระบบควบคุมมอเตอร์ (โปรเจกต์รถตัดหญ้า)

---

## 📱 คุณสมบัติหลัก

* 🔵 เชื่อมต่อ Bluetooth (Classic SPP)
* 📊 แสดงข้อมูล Telemetry แบบเรียลไทม์

  * แรงดัน (Volt)
  * กระแส (Current)
  * อุณหภูมิ (Temp)
* 🟢 แสดงสถานะการเชื่อมต่อ
* 🔴 ปุ่ม STOP ส่งคำสั่งหยุดระบบ

---

## 🧰 เทคโนโลยีที่ใช้

* Android (Java)
* Bluetooth Classic (SPP UUID)
* Foreground Service
* BroadcastReceiver (รับข้อมูลจาก Service)

---

## ⚙️ ความต้องการระบบ

* Android 7.0 (API 24) ขึ้นไป
* Android 12+ ต้องอนุญาต:

  * BLUETOOTH_CONNECT
  * BLUETOOTH_SCAN
* Android 13+ ต้องอนุญาต:

  * POST_NOTIFICATIONS

---

## 🔧 การติดตั้ง (Developer)

1. Clone โปรเจกต์

```
git clone https://github.com/USERNAME/TNMower2.git
```

2. เปิดด้วย Android Studio

3. กด Sync Gradle

4. กด Run ▶️ ลงมือถือ

---

## 📡 การใช้งาน

1. เปิด Bluetooth ที่มือถือ
2. เปิดแอป TN Mower
3. กดปุ่ม Connect
4. ระบบจะเชื่อมต่อกับอุปกรณ์ (MAC address ที่ตั้งไว้ในโค้ด)
5. ข้อมูล Telemetry จะถูกแสดงบนหน้าจอ

---

## 🔌 การตั้งค่า Bluetooth

แก้ MAC Address ในไฟล์:

```
BluetoothService.java
```

ตัวอย่าง:

```java
private final String MAC = "00:21:13:00:00:00";
```

---

## 🧠 โครงสร้างโปรเจกต์

app/
 ├── src/
 │    ├── main/
 │    │    ├── java/com/tnmower/tnmower/
 │    │    │    ├── ui/
 │    │    │    │    ├── MainActivity.java
 │    │    │    │    └── GaugeView.java
 │    │    │    │
 │    │    │    ├── bluetooth/
 │    │    │    │    └── BluetoothService.java
 │    │    │    │
 │    │    │    └── utils/
 │    │    │         └── CRCUtil.java
 │    │    │
 │    │    ├── res/
 │    │    │    ├── layout/
 │    │    │    │    └── activity_main.xml
 │    │    │    │
 │    │    │    ├── drawable/
 │    │    │    │    └── (background / shape ต่าง ๆ)
 │    │    │    │
 │    │    │    ├── values/
 │    │    │    │    ├── strings.xml
 │    │    │    │    ├── colors.xml
 │    │    │    │    └── themes.xml
 │    │    │    │
 │    │    │    └── values-th/
 │    │    │         └── strings.xml   ← ภาษาไทย
 │    │    │
 │    │    └── AndroidManifest.xml
 │    │
 │    └── test/
 │
 └── build.gradle
---

## ⚠️ หมายเหตุสำคัญ

* Bluetooth Emulator ใช้งานไม่ได้ → ต้องใช้มือถือจริง
* ต้องจับคู่ (pair) Bluetooth ก่อนใช้งาน
* MAC Address ต้องตรงกับอุปกรณ์จริง

---

## 🚀 แผนพัฒนาต่อ

* เพิ่ม Auto reconnect
* เพิ่ม UI แสดงกราฟ
* รองรับหลายอุปกรณ์
* เพิ่มระบบ Logging ลงไฟล์

---

## 👤 ผู้พัฒนา

* TN Mower Project

---

## 📜 License

สำหรับใช้งานส่วนตัว / โปรเจกต์ทดลอง
