//! Plain snapshots of the server state handed to the UI.

use std::collections::{HashMap, HashSet};

use base64::prelude::*;
use tsclientlib::data;
use tsclientlib::MaxClients;

#[derive(Clone, Debug, uniffi::Record)]
pub struct ServerView {
    pub name: String,
    pub welcome_message: String,
    pub host_message: String,
    pub platform: String,
    pub version: String,
    pub max_clients: u16,
    pub own_client_id: u16,
    pub own_channel_id: u64,
    pub banner_url: String,
    pub banner_gfx_url: String,
    pub icon_id: u32,
    pub host_message_mode: u8,
    pub banner_mode: u8,
    pub banner_interval_secs: u32,
    pub button_tooltip: String,
    pub button_url: String,
    pub button_gfx_url: String,
    pub default_server_group: u64,
    pub default_channel_group: u64,
    pub created: i64,
    pub uptime_secs: Option<u64>,
    pub has_password: Option<bool>,
    pub min_clients_forced_silence: Option<u32>,
    pub priority_speaker_dimm: f32,
    pub phonetic_name: String,
    pub ask_for_privilege_key: bool,
    /// Channels in display (tree) order.
    pub channels: Vec<ChannelView>,
    pub clients: Vec<ClientView>,
    pub server_groups: Vec<GroupView>,
    pub channel_groups: Vec<GroupView>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ChannelView {
    pub id: u64,
    pub parent_id: u64,
    pub depth: u32,
    pub name: String,
    pub topic: String,
    pub description: Option<String>,
    pub has_password: bool,
    pub is_default: bool,
    pub is_permanent: bool,
    pub is_semi_permanent: bool,
    /// -1 = unlimited
    pub max_clients: i32,
    pub codec: String,
    pub codec_quality: u8,
    pub needed_talk_power: i32,
    pub subscribed: bool,
    pub icon_id: u32,
    pub client_count: u32,
    pub has_children: bool,
    pub max_family_clients: i32,
    pub phonetic_name: String,
    pub delete_delay_secs: u64,
    pub is_unencrypted: bool,
    pub forced_silence: bool,
    pub is_private: bool,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ClientView {
    pub id: u16,
    pub database_id: u64,
    pub uid: String,
    pub name: String,
    pub channel_id: u64,
    pub is_query: bool,
    pub input_muted: bool,
    pub output_muted: bool,
    pub input_hardware: bool,
    pub output_hardware: bool,
    pub away: bool,
    pub away_message: String,
    pub talk_power: i32,
    pub is_talker: bool,
    pub talk_request: bool,
    pub talk_request_message: String,
    pub is_channel_commander: bool,
    pub is_priority_speaker: bool,
    pub is_recording: bool,
    pub server_groups: Vec<u64>,
    pub channel_group: u64,
    pub country: String,
    pub description: String,
    pub talking: bool,
    pub locally_muted: bool,
    pub icon_id: u32,
    pub avatar_hash: String,
    pub badges: String,
    pub phonetic_name: String,
    pub channel_group_inherited: u64,
    pub version: String,
    pub platform: String,
    pub created: i64,
    pub last_connected: i64,
    pub total_connections: u32,
    pub idle_secs: Option<u64>,
    pub ping_ms: Option<u32>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct GroupView {
    pub id: u64,
    pub name: String,
    pub sort_id: i32,
    pub icon_id: u32,
    pub is_template: bool,
    /// 0 = no name shown, 1 = before the client name, 2 = after it.
    pub naming_mode: u8,
}

fn max_clients(m: Option<&MaxClients>) -> i32 {
    match m {
        Some(MaxClients::Limited(n)) => *n as i32,
        _ => -1,
    }
}

pub fn build(con: &data::Connection, talking: &HashSet<u16>, local_muted: &HashSet<u16>) -> ServerView {
    let mut by_parent: HashMap<u64, Vec<&data::Channel>> = HashMap::new();
    for c in con.channels.values() {
        by_parent.entry(c.parent.0).or_default().push(c);
    }
    let mut counts: HashMap<u64, u32> = HashMap::new();
    for cl in con.clients.values() {
        *counts.entry(cl.channel.0).or_default() += 1;
    }

    let mut channels = Vec::with_capacity(con.channels.len());
    walk(0, 0, &by_parent, &counts, &mut channels);

    let mut clients: Vec<ClientView> = con
        .clients
        .values()
        .map(|c| ClientView {
            id: c.id.0,
            database_id: c.database_id.0,
            uid: c.uid.as_ref().map(|u| BASE64_STANDARD.encode(&u.0)).unwrap_or_default(),
            name: c.name.clone(),
            channel_id: c.channel.0,
            is_query: !matches!(c.client_type, tsclientlib::ClientType::Normal),
            input_muted: c.input_muted,
            output_muted: c.output_muted,
            input_hardware: c.input_hardware_enabled,
            output_hardware: c.output_hardware_enabled,
            away: c.away_message.is_some(),
            away_message: c.away_message.clone().unwrap_or_default(),
            talk_power: c.talk_power,
            is_talker: c.talk_power_granted,
            talk_request: c.talk_power_request.is_some(),
            talk_request_message: c.talk_power_request.as_ref().map(|r| r.message.clone()).unwrap_or_default(),
            is_channel_commander: c.is_channel_commander,
            is_priority_speaker: c.is_priority_speaker,
            is_recording: c.is_recording,
            server_groups: c.server_groups.iter().map(|g| g.0).collect(),
            channel_group: c.channel_group.0,
            country: c.country_code.clone(),
            description: c.description.clone(),
            talking: talking.contains(&c.id.0),
            locally_muted: local_muted.contains(&c.id.0),
            icon_id: c.icon.0,
            avatar_hash: c.avatar_hash.clone(),
            badges: c.badges.clone(),
            phonetic_name: c.phonetic_name.clone(),
            channel_group_inherited: c.inherited_channel_group_from_channel.0,
            version: c.optional_data.as_ref().map(|d| d.version.clone()).unwrap_or_default(),
            platform: c.optional_data.as_ref().map(|d| d.platform.clone()).unwrap_or_default(),
            created: c.optional_data.as_ref().map(|d| d.created.unix_timestamp()).unwrap_or(0),
            last_connected: c.optional_data.as_ref().map(|d| d.last_connected.unix_timestamp()).unwrap_or(0),
            total_connections: c.optional_data.as_ref().map(|d| d.connections_total).unwrap_or(0),
            idle_secs: c.connection_data.as_ref().map(|d| d.idle_time.whole_seconds() as u64),
            ping_ms: c.connection_data.as_ref().and_then(|d| d.ping).map(|p| p.whole_milliseconds() as u32),
        })
        .collect();
    clients.sort_by(|a, b| b.talk_power.cmp(&a.talk_power).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));

    let group = |id: u64, name: &str, sort_id: i32, icon: u32, t: bool, mode: u8| GroupView {
        id, name: name.to_string(), sort_id, icon_id: icon, is_template: t, naming_mode: mode,
    };
    let mut server_groups: Vec<GroupView> = con
        .server_groups
        .values()
        .map(|g| group(g.id.0, &g.name, g.sort_id, g.icon.0, !matches!(g.group_type, tsclientlib::GroupType::Regular), g.naming_mode as u8))
        .collect();
    server_groups.sort_by_key(|g| (g.sort_id, g.id));
    let mut channel_groups: Vec<GroupView> = con
        .channel_groups
        .values()
        .map(|g| group(g.id.0, &g.name, g.sort_id, g.icon.0, !matches!(g.group_type, tsclientlib::GroupType::Regular), g.naming_mode as u8))
        .collect();
    channel_groups.sort_by_key(|g| (g.sort_id, g.id));

    let s = &con.server;
    ServerView {
        name: s.name.clone(),
        welcome_message: s.welcome_message.clone(),
        host_message: s.hostmessage.clone(),
        platform: s.platform.clone(),
        version: s.version.clone(),
        max_clients: s.max_clients,
        own_client_id: con.own_client.0,
        own_channel_id: con.clients.get(&con.own_client).map(|c| c.channel.0).unwrap_or(0),
        banner_url: s.hostbanner_url.clone(),
        banner_gfx_url: s.hostbanner_gfx_url.clone(),
        icon_id: s.icon.0,
        host_message_mode: s.hostmessage_mode as u8,
        banner_mode: s.hostbanner_mode as u8,
        banner_interval_secs: s.hostbanner_gfx_interval.whole_seconds() as u32,
        button_tooltip: s.hostbutton_tooltip.clone(),
        button_url: s.hostbutton_url.clone(),
        button_gfx_url: s.hostbutton_gfx_url.clone(),
        default_server_group: s.default_server_group.0,
        default_channel_group: s.default_channel_group.0,
        created: s.created.unix_timestamp(),
        uptime_secs: s.optional_data.as_ref().map(|d| d.uptime.whole_seconds() as u64),
        has_password: s.optional_data.as_ref().map(|d| d.has_password),
        min_clients_forced_silence: s.optional_data.as_ref().map(|d| d.min_clients_in_channel_before_forced_silence),
        priority_speaker_dimm: s.priority_speaker_dimm_modificator,
        phonetic_name: s.phonetic_name.clone(),
        ask_for_privilege_key: s.ask_for_privilegekey,
        channels,
        clients,
        server_groups,
        channel_groups,
    }
}

/// Siblings are a linked list: `order` is the id of the channel above (0 = first).
fn walk(parent: u64, depth: u32, by_parent: &HashMap<u64, Vec<&data::Channel>>, counts: &HashMap<u64, u32>, out: &mut Vec<ChannelView>) {
    let Some(sibs) = by_parent.get(&parent) else { return };
    let mut ordered: Vec<&data::Channel> = Vec::with_capacity(sibs.len());
    let mut prev = 0u64;
    while let Some(c) = sibs.iter().find(|c| c.order.0 == prev && !ordered.iter().any(|o| o.id == c.id)) {
        ordered.push(c);
        prev = c.id.0;
    }
    let mut rest: Vec<&data::Channel> = sibs.iter().copied().filter(|c| !ordered.iter().any(|o| o.id == c.id)).collect();
    rest.sort_by_key(|c| c.id.0);
    ordered.extend(rest);

    for c in ordered {
        let ct = format!("{:?}", c.channel_type);
        out.push(ChannelView {
            id: c.id.0,
            parent_id: parent,
            depth,
            name: c.name.clone(),
            topic: c.topic.clone().unwrap_or_default(),
            description: c.optional_data.as_ref().map(|d| d.description.clone()),
            has_password: c.has_password.unwrap_or(false),
            is_default: c.is_default.unwrap_or(false),
            is_permanent: ct == "Permanent",
            is_semi_permanent: ct == "SemiPermanent",
            max_clients: max_clients(c.max_clients.as_ref()),
            codec: format!("{:?}", c.codec),
            codec_quality: c.codec_quality.unwrap_or(0),
            needed_talk_power: c.needed_talk_power.unwrap_or(0),
            subscribed: c.subscribed,
            icon_id: c.icon.map(|i| i.0).unwrap_or(0),
            client_count: counts.get(&c.id.0).copied().unwrap_or(0),
            has_children: by_parent.get(&c.id.0).is_some_and(|v| !v.is_empty()),
            max_family_clients: max_clients(c.max_family_clients.as_ref()),
            phonetic_name: c.phonetic_name.clone().unwrap_or_default(),
            delete_delay_secs: c.delete_delay.map(|d| d.whole_seconds() as u64).unwrap_or(0),
            is_unencrypted: c.is_unencrypted.unwrap_or(false),
            forced_silence: c.forced_silence,
            is_private: c.is_private.unwrap_or(false),
        });
        walk(c.id.0, depth + 1, by_parent, counts, out);
    }
}
