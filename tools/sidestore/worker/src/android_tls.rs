//! Standalone Android executables have no JavaVM. Use the OS-owned CA files
//! instead of rustls-platform-verifier's JNI path; never disable TLS checks.
pub fn builder() -> reqwest::ClientBuilder {
    let builder = reqwest::Client::builder().no_proxy().timeout(std::time::Duration::from_secs(60))
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            if attempt.url().scheme() == "https" && attempt.previous().len() < 5 { attempt.follow() }
            else { attempt.stop() }
        }));
    #[cfg(target_os = "android")]
    {
        let mut certs = Vec::new();
        for directory in ["/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts"] {
            if let Ok(entries) = std::fs::read_dir(directory) {
                for entry in entries.flatten() {
                    if let Ok(data) = std::fs::read(entry.path()) {
                        if let Ok(cert) = reqwest::Certificate::from_pem(&data) { certs.push(cert); }
                    }
                }
            }
        }
        builder.tls_certs_only(certs)
    }
    #[cfg(not(target_os = "android"))]
    { builder }
}
