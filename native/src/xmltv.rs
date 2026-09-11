//! XMLTV parse: a feed in, a flat byte record out.
//!
//! Mirrors `XmltvParser.parse` in Kotlin for the fields the EPG sync uses (channel id, start,
//! stop, title) - the parity test feeds both the same fixture. The output is one little-endian
//! byte blob, decoded on the Kotlin side, so the JNI boundary is crossed once per feed:
//!
//! ```text
//! u32 version (1)
//! u32 idCount,        idCount × (u16 len, utf8)         channel ids, declared or only referenced
//! u32 channelCount,   channelCount × (u32 idIdx, u16 len, utf8 displayName)
//! u16 zoneCount,      zoneCount × (u16 len, utf8)       zone suffixes Kotlin has to resolve; 0 = none
//! u32 programmeCount, programmeCount × (u32 idIdx, i64 start, i64 stop, u16 startZone,
//!                                       u16 stopZone, u16 len, utf8 title)
//! ```
//!
//! A stamp with a numeric offset is resolved here. One with no zone, or a zone name, is emitted
//! as its wall-clock seconds plus the zone string (empty = local time), because only the
//! platform knows the device's zone and its DST rules; Kotlin finishes those.

use quick_xml::escape::unescape;
use quick_xml::events::Event;
use quick_xml::Reader;
use std::collections::HashMap;
use std::io::BufRead;

/// Same caps as the Kotlin parser; the plan treats lifting them as a separate decision.
pub const MAX_CHANNELS: usize = 10_000;
pub const MAX_PROGRAMMES: usize = 50_000;
pub const VERSION: u32 = 1;

#[derive(Default)]
struct Tables {
    ids: Vec<u8>,
    id_count: u32,
    id_index: HashMap<String, u32>,
    zones: Vec<u8>,
    zone_count: u16,
    zone_index: HashMap<String, u16>,
    channels: Vec<u8>,
    channel_count: u32,
    programmes: Vec<u8>,
    programme_count: u32,
}

impl Tables {
    fn id(&mut self, id: &str) -> u32 {
        if let Some(&i) = self.id_index.get(id) {
            return i;
        }
        let i = self.id_count;
        put_str(&mut self.ids, id);
        self.id_count += 1;
        self.id_index.insert(id.to_owned(), i);
        i
    }

    /// 0 is reserved for "already an instant"; the first real zone is 1.
    fn zone(&mut self, zone: &str) -> u16 {
        if let Some(&i) = self.zone_index.get(zone) {
            return i;
        }
        let i = self.zone_count + 1;
        put_str(&mut self.zones, zone);
        self.zone_count += 1;
        self.zone_index.insert(zone.to_owned(), i);
        i
    }
}

fn put_str(out: &mut Vec<u8>, s: &str) {
    let bytes = s.as_bytes();
    // u16 length: a title or id past 64 KB is not one the guide could show anyway.
    let n = bytes.len().min(u16::MAX as usize);
    out.extend_from_slice(&(n as u16).to_le_bytes());
    out.extend_from_slice(&bytes[..n]);
}

/// Text as the Kotlin pull parser hands it over: entities expanded, malformed UTF-8 replaced.
fn text(raw: &[u8]) -> Result<String, String> {
    let s = String::from_utf8_lossy(raw);
    unescape(&s).map(|c| c.into_owned()).map_err(|e| format!("bad entity: {e}"))
}

pub fn parse<R: BufRead>(input: R) -> Result<Vec<u8>, String> {
    let mut reader = Reader::from_reader(input);
    reader.config_mut().trim_text(false);
    let mut buf = Vec::with_capacity(8 * 1024);
    let mut t = Tables::default();

    // What the cursor is inside of. Element-local state lives here rather than in nested
    // loops so one event pump serves the whole document.
    let mut in_channel: Option<(String, String)> = None; // (id, display-name)
    let mut in_programme: Option<(String, String, String, String)> = None; // (channel, start, stop, title)
    // Set on <title> / <display-name>: the Kotlin parser takes the one event after the start
    // tag as the text, so only the first event after it counts, whatever it is.
    let mut want_text: Option<&'static str> = None;

    loop {
        let event = reader
            .read_event_into(&mut buf)
            .map_err(|e| format!("XML error at byte {}: {e}", reader.buffer_position()))?;
        // Only the event right after <title>/<display-name> can be its text.
        let wanted = want_text.take();
        match &event {
            Event::Start(e) | Event::Empty(e) => {
                let name = e.name();
                let empty = matches!(event, Event::Empty(_));
                match name.as_ref() {
                    b"channel" if in_channel.is_none() && in_programme.is_none() => {
                        let id = attr(e, b"id")?.unwrap_or_default();
                        if empty {
                            add_channel(&mut t, &id, "");
                        } else {
                            in_channel = Some((id, String::new()));
                        }
                    }
                    b"programme" if in_channel.is_none() && in_programme.is_none() => {
                        let channel = attr(e, b"channel")?.unwrap_or_default();
                        let start = attr(e, b"start")?.unwrap_or_default();
                        let stop = attr(e, b"stop")?.unwrap_or_default();
                        if empty {
                            add_programme(&mut t, &channel, &start, &stop, "");
                        } else {
                            in_programme = Some((channel, start, stop, String::new()));
                        }
                    }
                    b"display-name" if in_channel.is_some() && !empty => want_text = Some("display-name"),
                    b"title" if in_programme.is_some() && !empty => want_text = Some("title"),
                    _ => {}
                }
            }
            Event::Text(e) => {
                if let Some(which) = wanted {
                    let s = text(e)?;
                    match which {
                        "title" => in_programme.as_mut().unwrap().3 = s,
                        _ => in_channel.as_mut().unwrap().1 = s,
                    }
                }
            }
            Event::CData(e) => {
                if let Some(which) = wanted {
                    let s = String::from_utf8_lossy(e).into_owned();
                    match which {
                        "title" => in_programme.as_mut().unwrap().3 = s,
                        _ => in_channel.as_mut().unwrap().1 = s,
                    }
                }
            }
            Event::End(e) => match e.name().as_ref() {
                b"channel" => {
                    if let Some((id, name)) = in_channel.take() {
                        add_channel(&mut t, &id, &name);
                    }
                }
                b"programme" => {
                    if let Some((channel, start, stop, title)) = in_programme.take() {
                        add_programme(&mut t, &channel, &start, &stop, &title);
                    }
                }
                _ => {}
            },
            Event::Eof => {
                if in_channel.is_some() || in_programme.is_some() {
                    // The Kotlin pull parser throws on a feed cut off inside an element.
                    return Err("unexpected end of feed inside an element".into());
                }
                break;
            }
            _ => {}
        }
        buf.clear();
    }

    let mut out = Vec::with_capacity(
        16 + t.ids.len() + t.channels.len() + t.zones.len() + t.programmes.len() + 8,
    );
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&t.id_count.to_le_bytes());
    out.extend_from_slice(&t.ids);
    out.extend_from_slice(&t.channel_count.to_le_bytes());
    out.extend_from_slice(&t.channels);
    out.extend_from_slice(&t.zone_count.to_le_bytes());
    out.extend_from_slice(&t.zones);
    out.extend_from_slice(&t.programme_count.to_le_bytes());
    out.extend_from_slice(&t.programmes);
    Ok(out)
}

fn attr(e: &quick_xml::events::BytesStart, key: &[u8]) -> Result<Option<String>, String> {
    for a in e.attributes().with_checks(false) {
        let a = a.map_err(|e| format!("bad attribute: {e}"))?;
        if a.key.as_ref() == key {
            return text(&a.value).map(Some);
        }
    }
    Ok(None)
}

fn add_channel(t: &mut Tables, id: &str, name: &str) {
    if t.channel_count as usize >= MAX_CHANNELS {
        return;
    }
    let idx = t.id(id);
    t.channels.extend_from_slice(&idx.to_le_bytes());
    put_str(&mut t.channels, name);
    t.channel_count += 1;
}

fn add_programme(t: &mut Tables, channel: &str, start: &str, stop: &str, title: &str) {
    if t.programme_count as usize >= MAX_PROGRAMMES {
        return;
    }
    let idx = t.id(channel);
    let (start_s, start_zone) = parse_date(start);
    let (stop_s, stop_zone) = parse_date(stop);
    let start_zone = start_zone.map_or(0, |z| t.zone(z));
    let stop_zone = stop_zone.map_or(0, |z| t.zone(z));
    t.programmes.extend_from_slice(&idx.to_le_bytes());
    t.programmes.extend_from_slice(&start_s.to_le_bytes());
    t.programmes.extend_from_slice(&stop_s.to_le_bytes());
    t.programmes.extend_from_slice(&start_zone.to_le_bytes());
    t.programmes.extend_from_slice(&stop_zone.to_le_bytes());
    put_str(&mut t.programmes, title);
    t.programme_count += 1;
}

/// `XmltvParser.parseXmltvDate`, minus the platform zone lookups: `(seconds, zone)` where a
/// `None` zone means `seconds` is the instant, and `Some(z)` means it is the wall-clock reading
/// that Kotlin still has to shift by zone `z` (`""` = the device's local zone).
pub fn parse_date(s: &str) -> (i64, Option<&str>) {
    let s = s.trim();
    let b = s.as_bytes();
    let mut end = 0;
    while end < b.len() && b[end].is_ascii_digit() {
        end += 1;
    }
    if end < 8 {
        return (0, None);
    }
    let n = end.min(14);
    let digits = |from: usize, to: usize| -> i64 { b[from..to].iter().fold(0, |v, c| v * 10 + (c - b'0') as i64) };
    let year = digits(0, 4);
    let month = digits(4, 6);
    let day = digits(6, 8);
    let hour = if n >= 10 { digits(8, 10) } else { 0 };
    let minute = if n >= 12 { digits(10, 12) } else { 0 };
    let second = if n >= 14 { digits(12, 14) } else { 0 };
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) || hour > 23 || minute > 59 || second > 59 {
        return (0, None);
    }
    let wall = days_from_civil(year, month, day) * 86_400 + hour * 3600 + minute * 60 + second;

    let mut i = end;
    while i < b.len() && (b[i] == b' ' || b[i] == b'\t') {
        i += 1;
    }
    if i >= b.len() {
        return (wall, Some(""));
    }
    let sign = match b[i] {
        b'+' => 1,
        b'-' => -1,
        _ => 0,
    };
    if sign != 0 {
        i += 1;
        let Some(hours) = digits_or_none(b, i, i + 2) else { return (0, None) };
        i += 2;
        if i < b.len() && b[i] == b':' {
            i += 1;
        }
        let minutes = if i + 2 <= b.len() { digits_or_none(b, i, i + 2).unwrap_or(0) } else { 0 };
        return (wall - sign * (hours * 3600 + minutes * 60), None);
    }
    (wall, Some(&s[i..]))
}

fn digits_or_none(b: &[u8], from: usize, to: usize) -> Option<i64> {
    if to > b.len() {
        return None;
    }
    let mut v = 0i64;
    for &c in &b[from..to] {
        if !c.is_ascii_digit() {
            return None;
        }
        v = v * 10 + (c - b'0') as i64;
    }
    Some(v)
}

/// Howard Hinnant's days_from_civil, as in the Kotlin parser.
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let doy = (153 * (if month > 2 { month - 3 } else { month + 9 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dates_match_the_kotlin_parser() {
        // Cases from XmltvDateParsingTest.
        assert_eq!(parse_date("20260910220000 +0000"), (1_789_077_600, None));
        assert_eq!(parse_date("20260910220000 +0200"), (1_789_077_600 - 7200, None));
        assert_eq!(parse_date("20260910220000 -05:30"), (1_789_077_600 + 5 * 3600 + 1800, None));
        assert_eq!(parse_date("20260910"), (1_788_998_400, Some("")));
        assert_eq!(parse_date("20260910220000 Europe/Paris"), (1_789_077_600, Some("Europe/Paris")));
        assert_eq!(parse_date("2026091"), (0, None));
        assert_eq!(parse_date("20261310000000 +0000"), (0, None));
        assert_eq!(parse_date("20260910220000 +0x"), (0, None));
        assert_eq!(parse_date(""), (0, None));
    }

    fn decode(blob: &[u8]) -> (Vec<String>, Vec<(u32, String)>, Vec<String>, Vec<(u32, i64, i64, u16, u16, String)>) {
        let mut p = 0usize;
        let u16_at = |p: &mut usize| { let v = u16::from_le_bytes(blob[*p..*p + 2].try_into().unwrap()); *p += 2; v };
        let u32_at = |p: &mut usize| { let v = u32::from_le_bytes(blob[*p..*p + 4].try_into().unwrap()); *p += 4; v };
        let i64_at = |p: &mut usize| { let v = i64::from_le_bytes(blob[*p..*p + 8].try_into().unwrap()); *p += 8; v };
        let str_at = |p: &mut usize| { let n = u16_at(p) as usize; let s = String::from_utf8(blob[*p..*p + n].to_vec()).unwrap(); *p += n; s };
        assert_eq!(u32_at(&mut p), VERSION);
        let ids = (0..u32_at(&mut p)).map(|_| str_at(&mut p)).collect();
        let channels = (0..u32_at(&mut p)).map(|_| (u32_at(&mut p), str_at(&mut p))).collect();
        let zones = (0..u16_at(&mut p)).map(|_| str_at(&mut p)).collect();
        let programmes = (0..u32_at(&mut p))
            .map(|_| (u32_at(&mut p), i64_at(&mut p), i64_at(&mut p), u16_at(&mut p), u16_at(&mut p), str_at(&mut p)))
            .collect();
        assert_eq!(p, blob.len());
        (ids, channels, zones, programmes)
    }

    #[test]
    fn feed_round_trips() {
        let xml = br#"<?xml version="1.0" encoding="UTF-8"?>
<tv><channel id="a.tv"><display-name>A &amp; B</display-name><icon src="x"/></channel>
<channel id="empty.tv"/>
<programme start="20260910220000 +0000" stop="20260910230000 +0000" channel="a.tv"><title lang="en">Caf&#233; <![CDATA[x]]></title><desc>d</desc><credits><actor>A</actor></credits></programme>
<programme start="20260910230000" stop="20260911000000 Europe/Paris" channel="ghost.tv"><title></title></programme>
<programme start="1" stop="2" channel="a.tv"/>
</tv>"#;
        let (ids, channels, zones, programmes) = decode(&parse(&xml[..]).unwrap());
        assert_eq!(ids, ["a.tv", "empty.tv", "ghost.tv"]);
        assert_eq!(channels, [(0, "A & B".into()), (1, String::new())]);
        assert_eq!(zones, ["", "Europe/Paris"]);
        assert_eq!(programmes[0], (0, 1_789_077_600, 1_789_081_200, 0, 0, "Café ".into()));
        assert_eq!(programmes[1], (2, 1_789_081_200, 1_789_084_800, 1, 2, String::new()));
        assert_eq!(programmes[2], (0, 0, 0, 0, 0, String::new()));
    }

    #[test]
    fn truncated_feed_is_an_error() {
        assert!(parse(&b"<tv><programme channel=\"a\"><title>x"[..]).is_err());
    }
}
