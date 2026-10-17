//! Generic TS3 command parsing for notifications the snapshot does not model.

use crate::KeyValue;

pub fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match it.next() {
            Some('s') => out.push(' '),
            Some('p') => out.push('|'),
            Some('/') => out.push('/'),
            Some('\\') => out.push('\\'),
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('t') => out.push('\t'),
            Some('v') => out.push('\u{0b}'),
            Some('f') => out.push('\u{0c}'),
            Some('a') => out.push('\u{07}'),
            Some('b') => out.push('\u{08}'),
            Some(o) => out.push(o),
            None => {}
        }
    }
    out
}

/// `name k=v k=v|k=v` -> (name, parts). Keys of the first part are inherited by later parts.
pub fn parse(line: &str) -> (String, Vec<Vec<KeyValue>>) {
    let mut parts: Vec<Vec<KeyValue>> = Vec::new();
    let mut name = String::new();
    for (i, chunk) in line.split('|').enumerate() {
        let mut kv = Vec::new();
        for (j, tok) in chunk.split(' ').filter(|t| !t.is_empty()).enumerate() {
            match tok.split_once('=') {
                Some((k, v)) => kv.push(KeyValue { key: k.to_string(), value: unescape(v) }),
                None if i == 0 && j == 0 => name = tok.to_string(),
                None => kv.push(KeyValue { key: tok.to_string(), value: String::new() }),
            }
        }
        parts.push(kv);
    }
    if parts.len() > 1 {
        let first = parts[0].clone();
        for p in parts.iter_mut().skip(1) {
            for kv in &first {
                if !p.iter().any(|x| x.key == kv.key) {
                    p.push(kv.clone());
                }
            }
        }
    }
    (name, parts)
}

/// Notifications already reflected in the snapshot/events; not forwarded to the UI.
pub fn is_book_command(name: &str) -> bool {
    matches!(
        name,
        "initserver"
            | "channellist"
            | "channellistfinished"
            | "notifycliententerview"
            | "notifyclientleftview"
            | "notifyclientmoved"
            | "notifyclientupdated"
            | "notifychannelcreated"
            | "notifychanneledited"
            | "notifychannelmoved"
            | "notifychanneldeleted"
            | "notifychannelchanged"
            | "notifychannelsubscribed"
            | "notifychannelunsubscribed"
            | "notifytextmessage"
            | "notifyclientpoke"
            | "notifyserveredited"
            | "error"
            | "notifystartdownload"
            | "notifystartupload"
            | "notifystatusfiletransfer"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_lists_and_escapes() {
        let (n, p) = parse("notifybanlist banid=1 ip=1.2.3.4 reason=bad\\sguy|banid=2 reason=x\\pa");
        assert_eq!(n, "notifybanlist");
        assert_eq!(p.len(), 2);
        assert_eq!(p[0][2].value, "bad guy");
        assert_eq!(p[1].iter().find(|k| k.key == "reason").unwrap().value, "x|a");
        assert_eq!(p[1].iter().find(|k| k.key == "ip").unwrap().value, "1.2.3.4");
    }
}
