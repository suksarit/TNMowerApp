# 🌿 TN Mower

แอป Android สำหรับควบคุมและรับข้อมูลจากระบบ **TN Mower** ผ่าน Bluetooth  
ใช้ร่วมกับ Arduino / ระบบควบคุมมอเตอร์ (รถตัดหญ้า)

---

## 📱 คุณสมบัติหลัก

- 🔵 เชื่อมต่อ Bluetooth (Classic SPP)
- 📊 แสดงข้อมูลแบบเรียลไทม์
  - แรงดัน (Voltage)
  - กระแส (Current)
  - อุณหภูมิ (Temperature)
- 🟢 แสดงสถานะการเชื่อมต่อ
- 🔴 ปุ่ม STOP สำหรับหยุดระบบทันที

---

## 🧰 เทคโนโลยีที่ใช้

- Android (Java)
- Bluetooth Classic (SPP UUID)
- Foreground Service
- BroadcastReceiver

---

## ⚙️ ความต้องการระบบ

- Android 7.0 (API 24) ขึ้นไป

### Android 12+
- BLUETOOTH_CONNECT
- BLUETOOTH_SCAN

### Android 13+
- POST_NOTIFICATIONS

---

## 🔧 การติดตั้ง

```bash
git clone https://github.com/USERNAME/TNMower2.git
```

1. เปิดโปรเจกต์ด้วย Android Studio  
2. Sync Gradle  
3. กด Run ▶️ ลงมือถือ  

---

## 📡 วิธีใช้งาน

1. เปิด Bluetooth ที่มือถือ  
2. เปิดแอป TN Mower  
3. กดปุ่ม Connect  
4. เชื่อมต่อกับอุปกรณ์  
5. ดูข้อมูล Telemetry บนหน้าจอ  

---

## 🔌 การตั้งค่า Bluetooth

แก้ MAC Address ในไฟล์:

```
app/src/main/java/com/tnmower/tnmower/bluetooth/BluetoothService.java
```

ตัวอย่าง:

```java
private final String MAC = "00:21:13:00:00:00";
```

---

## 🧠 โครงสร้างโปรเจกต์

```
app/
 ├── src/main/
 │    ├── java/com/tnmower/tnmower/
 │    │    ├── ui/
 │    │    │    ├── MainActivity.java
 │    │    │    └── GaugeView.java
 │    │    ├── bluetooth/
 │    │    │    └── BluetoothService.java
 │    │    └── utils/
 │    │         └── CRCUtil.java
 │    │
 │    ├── res/
 │    │    ├── layout/activity_main.xml
 │    │    ├── drawable/
 │    │    ├── values/
 │    │    └── values-th/
 │    │
 │    └── AndroidManifest.xml
```

---

## ⚠️ หมายเหตุสำคัญ

- Emulator ใช้งาน Bluetooth ไม่ได้ → ต้องใช้มือถือจริง  
- ต้องจับคู่ (Pair) Bluetooth ก่อนใช้งาน  
- MAC Address ต้องตรงกับอุปกรณ์  

---

## 🚀 แผนพัฒนา

- Auto reconnect
- แสดงกราฟข้อมูล
- รองรับหลายอุปกรณ์
- บันทึกข้อมูล (Logging)

---

## 👤 ผู้พัฒนา

TN Mower Project

---

## 📜 License

สำหรับใช้งานส่วนตัว / โปรเจกต์ทดลอง
