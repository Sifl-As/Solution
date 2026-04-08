# 画像前処理モジュール

鉄骨文字認識システムにおける **画像前処理** の検証・実装用 Android アプリです。  
撮影した鉄骨画像を文字認識エンジンに渡す前に、認識精度を高めるための前処理を行います。

---

## 前処理の流れ

入力画像に対して以下の 5 ステップを順番に適用します。

```
元画像（カラー）
    │
    ▼
① リサイズ         最長辺を 960px に縮小（アスペクト比維持）
    │
    ▼
② グレースケール化  BGR → Gray（1チャンネル化）
    │
    ▼
③ CLAHE            局所的なコントラスト補正（照明ムラ対策）
    │
    ▼
④ ガウシアンブラー  ノイズ除去（カーネルサイズ 5×5）
    │
    ▼
⑤ 適応的二値化      局所平均に基づく閾値処理（文字の白黒化）
    │
    ▼
前処理済み画像（白黒2値）
```

### パラメータ一覧

| ステップ | パラメータ | デフォルト値 |
|---------|-----------|------------|
| リサイズ | 最大サイズ | 960 px |
| CLAHE | クリップ制限（clipLimit） | 2.0 |
| CLAHE | タイルサイズ（tileGridSize） | 8×8 |
| ガウシアンブラー | カーネルサイズ | 5×5 |
| 適応的二値化 | ブロックサイズ（blockSize） | 11 |
| 適応的二値化 | 定数 C | 2.0 |

---

## ファイル構成

```
image-preprocessing/
├── app/
│   └── src/main/
│       ├── java/com/example/imagepreprocessingtest/
│       │   ├── ImagePreprocessor.kt   ← 前処理ロジック本体
│       │   └── MainActivity.kt        ← UI・画像表示・処理呼び出し
│       └── assets/images/             ← テスト用鉄骨画像 18 枚
├── opencv/                            ← OpenCV 4.11 ライブラリモジュール
├── build.gradle.kts
└── settings.gradle.kts
```

### 主要クラス

#### `ImagePreprocessor.kt`
前処理の全ロジックを担うクラスです。

| メソッド | 説明 |
|---------|------|
| `preprocess(bitmap)` | 5ステップの前処理を実行し、結果画像を返す |
| `preprocessWithTiming(bitmap)` | 処理時間も合わせて返す |
| `preprocessWithDetailedTiming(bitmap)` | 各ステップごとの処理時間を計測して返す |

#### `MainActivity.kt`
- アプリ起動時に OpenCV を初期化
- `assets/images/` からテスト画像を読み込み
- 「前処理実行」ボタンで `ImagePreprocessor` を呼び出し、元画像と結果を並べて表示
- 「詳細計測」ボタンで各ステップの処理時間を表示

---

## 動作確認結果

| 項目 | 結果 |
|------|------|
| テスト画像 | 鉄骨サンプル 18 枚（最大 5712 × 4284 px） |
| 処理時間（実測） | **1127 ms**（エミュレーター） |
| 目標（3秒以内） | ✅ 達成 |
| OpenCV 初期化 | ✅ 成功 |

---

## セットアップ・ビルド方法

### 必要な環境

| ツール | バージョン |
|-------|-----------|
| Android Studio | 2024.3.x（Meerkat）以降 |
| JDK | 21（Android Studio 同梱の JBR） |
| Android SDK | API 34 |
| NDK | 25.1.8937393 |
| Gradle | 8.4 |
| AGP | 8.3.2 |

### 手順

1. このフォルダを Android Studio で開く

```
File → Open → image-preprocessing/
```

2. Gradle sync が自動実行されるので完了を待つ

3. Android デバイス（実機 or エミュレーター）を接続して ▶ Run

### コマンドラインでビルドする場合

```bash
cd image-preprocessing
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk が生成される
```

Windows の場合：
```powershell
.\gradlew.bat assembleDebug
```

---

## 担当

坂井壱謙（画像処理班・前処理担当）  
使用ライブラリ: [OpenCV 4.11.0 for Android](https://opencv.org/)
