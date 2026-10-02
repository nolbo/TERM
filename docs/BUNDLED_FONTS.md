# Bundled fonts

Unmodified variable fonts from the official [Google Fonts repository](https://github.com/google/fonts), downloaded on 2026-10-02. Google Sans supports weights 400–700; Google Sans Code supports weights 300–800. Both include upright and italic faces.

Both families are licensed under SIL Open Font License 1.1. License copies ship in the APK assets: [Google Sans](../android-app/src/main/assets/licenses/GoogleSans-OFL.txt) and [Google Sans Code](../android-app/src/main/assets/licenses/GoogleSansCode-OFL.txt).

| App resource | Upstream file | SHA-256 |
| --- | --- | --- |
| `google_sans_regular.ttf` | [GoogleSans[GRAD,opsz,wght].ttf](https://github.com/google/fonts/blob/main/ofl/googlesans/GoogleSans%5BGRAD%2Copsz%2Cwght%5D.ttf) | `d0a87d835a944b8b40d0e82a5651bb59ab97b936a2aeed5946eb57e7b2a3a90a` |
| `google_sans_italic.ttf` | [GoogleSans-Italic[GRAD,opsz,wght].ttf](https://github.com/google/fonts/blob/main/ofl/googlesans/GoogleSans-Italic%5BGRAD%2Copsz%2Cwght%5D.ttf) | `b6662abd2131cad1b248750149a7f2b644f871ebcc9af7fe4cd1135cd290b6d1` |
| `google_sans_code_regular.ttf` | [GoogleSansCode[wght].ttf](https://github.com/google/fonts/blob/main/ofl/googlesanscode/GoogleSansCode%5Bwght%5D.ttf) | `c9649573afcd966f61096b1e9c3c3115e7b54315ca2adf9839c6f8b17d5f8e1f` |
| `google_sans_code_italic.ttf` | [GoogleSansCode-Italic[wght].ttf](https://github.com/google/fonts/blob/main/ofl/googlesanscode/GoogleSansCode-Italic%5Bwght%5D.ttf) | `bdf116292f27aca16e2aefb5534042a25f244d3a9187a521ce97fc2117a4a844` |

The ordinary UI fallback order is Google Sans → Pretendard → Android system fonts. The same ordered native family is exposed to Compose at each weight/style and assigned to the native release-notes TextView. Google Sans Code and terminal custom-font ordering are unchanged.

The unmodified `public/variable/PretendardVariable.ttf` from the official [Pretendard 1.3.9 release](https://github.com/orioncactus/pretendard/releases/tag/v1.3.9) was downloaded on 2026-10-03. The font provides a `wght` axis spanning 45–930; the app registers weights 100–900. Its [OFL license](../android-app/src/main/assets/licenses/Pretendard-OFL.txt) ships in the APK. Korean and supported kana retain the requested UI weight. Pretendard does not cover all of Noto Sans CJK KR's characters: unsupported Han such as `漢` uses Android system fonts, so its appearance can vary by device.

| App resource | Upstream file | SHA-256 |
| --- | --- | --- |
| `pretendard_variable.ttf` | [`public/variable/PretendardVariable.ttf` in Pretendard-1.3.9.zip](https://github.com/orioncactus/pretendard/releases/download/v1.3.9/Pretendard-1.3.9.zip) | `3090ccde0442bb347aa7685d9ba8b17436a60682df6e8f92a9a670de14056e22` |
