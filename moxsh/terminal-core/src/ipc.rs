//! IPC 加固握手（挑战-响应，HMAC-SHA256）。
//!
//! 解决 Termux `LocalServerSocket` 无鉴权的系统性缺陷：任何同 UID 应用都能连。
//! moxsh 在插件/宿主间建立连接时，由服务端下发 nonce，客户端用共享密钥
//! `HMAC-SHA256(nonce, secret)` 应答，服务端验签通过才授权。密钥在握手协商
//! 阶段通过安全通道（由 Kotlin 层用 Android Keystore 派生）注入。

use crate::crypto::{hmac_sha256, nonce_hex};

/// 服务端：生成挑战 nonce（十六进制字符串）。
pub fn issue_challenge() -> String {
    nonce_hex()
}

/// 客户端：根据 nonce 与共享密钥计算应答。
pub fn respond(challenge: &str, secret: &[u8]) -> String {
    let mac = hmac_sha256(secret, challenge.as_bytes());
    hex(&mac)
}

/// 服务端：校验应答是否正确。
pub fn verify(challenge: &str, response: &str, secret: &[u8]) -> bool {
    let expected = respond(challenge, secret);
    // 定长比较，避免时序侧信道
    if expected.len() != response.len() {
        return false;
    }
    let mut diff = 0u8;
    for (a, b) in expected.bytes().zip(response.bytes()) {
        diff |= a ^ b;
    }
    diff == 0
}

fn hex(data: &[u8]) -> String {
    data.iter().map(|x| format!("{:02x}", x)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handshake_ok() {
        let secret = b"shared-secret";
        let chal = issue_challenge();
        let resp = respond(&chal, secret);
        assert!(verify(&chal, &resp, secret));
        assert!(!verify(&chal, &resp, b"wrong"));
    }
}
