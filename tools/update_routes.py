"""Собирает IP-диапазоны для AllowedIPs клиентских конфигов.

Результат, по сети в строке, IPv4 и IPv6, уже схлопнутые:
- src/main/resources/routes.txt - что пускаем через VPN в режиме «только заблокированное»;
- src/main/resources/all.txt - режим «весь трафик»: всё, кроме DIRECT.
Запускать время от времени (диапазоны у сервисов и реестр блокировок меняются):
    python tools/update_routes.py
"""
import ipaddress
import json
import pathlib
import urllib.request

# Автономные системы целиком (анонсы берем из RIPEstat).
ASNS = {
    "Google / YouTube": ["AS15169", "AS36040", "AS43515", "AS36561"],
    "Meta (Instagram, Facebook, WhatsApp, Threads)": ["AS32934"],
    "Telegram": ["AS62041", "AS62014", "AS59930", "AS44907", "AS211157"],
    "X / Twitter": ["AS13414", "AS35995"],
    "Pornhub и др. сайты Aylo (Reflected Networks)": ["AS29789"],
    "CDN77 (xvideos и др.)": ["AS60068"],
    "Anthropic (Claude)": ["AS399358"],
}
# Готовые списки сетей, по строке на сеть.
LISTS = {
    # Курируемый сообществом список заблокированных популярных ресурсов.
    "antifilter community": "https://community.antifilter.download/list/community.lst",
    # Целиком заблокированные подсети из реестра РКН.
    "antifilter subnet": "https://antifilter.download/list/subnet.lst",
    "Telegram": "https://core.telegram.org/resources/cidr.txt",
    # Cloudflare в РФ режут целиком, а на нем Discord, ChatGPT, xhamster, Patreon, Medium и тысячи других.
    "Cloudflare v4": "https://www.cloudflare.com/ips-v4",
    "Cloudflare v6": "https://www.cloudflare.com/ips-v6",
}
# Отдельные заблокированные или ушедшие из РФ сайты, которых нет в списках выше:
# резолвим через DoH и берем их /24 (/48 для IPv6) - соседние адреса обычно тоже их.
DOMAINS = """
pornhub.com www.pornhub.com xvideos.com xnxx.com xhamster.com onlyfans.com
linkedin.com www.linkedin.com static.licdn.com media.licdn.com
discord.com discord.gg gateway.discord.gg cdn.discordapp.com media.discordapp.net
chatgpt.com openai.com api.openai.com cdn.oaistatic.com claude.ai anthropic.com
perplexity.ai grok.com x.ai copilot.microsoft.com
spotify.com open.spotify.com soundcloud.com patreon.com medium.com
notion.so figma.com canva.com
signal.org viber.com proton.me protonmail.com torproject.org
rutracker.org nnmclub.to rutor.info
roblox.com www.roblox.com rbxcdn.com
""".split()
# Всегда мимо VPN, в обоих режимах: банки с немецким адресом подвисают и требуют перезаходить.
DIRECT_ASNS = {
    "Т-Банк": ["AS43399"],
}
# CDN Т-Банка на EdgeCenter (картинки, конфиги приложения) - берем по домену, ASN у CDN общий.
DIRECT_DOMAINS = """
tbank.ru www.tbank.ru id.tbank.ru secure.tbank.ru tinkoff.ru api.tinkoff.ru
acdn.tinkoff.ru acdn.tbank.ru imgproxy.cdn-tinkoff.ru brands-prod.cdn-tinkoff.ru
""".split()
# DNS ходит через туннель, чтобы провайдер не подменял ответы, а YouTube отдавал ближние к VPS серверы.
DNS = ["1.1.1.1/32", "1.0.0.1/32", "2606:4700:4700::1111/128", "2606:4700:4700::1001/128"]


def fetch(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": "amnezia-friends-bot", **(headers or {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode()


def asn_prefixes(asn):
    data = json.loads(fetch(f"https://stat.ripe.net/data/announced-prefixes/data.json?resource={asn}"))
    return [p["prefix"] for p in data["data"]["prefixes"]]


def resolve(domain):
    result = []
    for rtype, wanted in (("A", 1), ("AAAA", 28)):
        answer = json.loads(fetch(f"https://cloudflare-dns.com/dns-query?name={domain}&type={rtype}",
                                  {"accept": "application/dns-json"})).get("Answer", [])
        result += [a["data"] for a in answer if a["type"] == wanted]
    return result


def widen(net):
    """Слишком мелкие сети расширяем до /24 (/48): маршрутов в конфиге меньше, а лишнее через VPN не мешает."""
    limit = 24 if net.version == 4 else 48
    return net.supernet(new_prefix=limit) if net.prefixlen > limit else net


def domain_nets(domains):
    nets = []
    for domain in domains:
        try:
            ips = resolve(domain)
        except Exception as e:  # один сломанный домен не должен ронять сборку списка
            print(f"  {domain}: не резолвится ({e})")
            continue
        nets += [ipaddress.ip_network(ip) for ip in ips]
    return nets


def exclude(nets, holes):
    """Сети [nets] без адресов из [holes]."""
    result = list(nets)
    for hole in holes:
        rest = []
        for net in result:
            if net.version != hole.version or not net.overlaps(hole):
                rest.append(net)
            elif not hole.subnet_of(net):
                continue  # сеть целиком внутри дыры
            else:
                rest += net.address_exclude(hole)
        result = rest
    return result


def write(name, nets):
    v4 = list(ipaddress.collapse_addresses(n for n in nets if n.version == 4))
    v6 = list(ipaddress.collapse_addresses(n for n in nets if n.version == 6))
    out = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources" / name
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(str(n) for n in v4 + v6) + "\n", newline="\n")
    print(f"{name}: {len(v4)} IPv4 + {len(v6)} IPv6 -> {out}")


def main():
    direct = []
    for name, asns in DIRECT_ASNS.items():
        found = [p for asn in asns for p in asn_prefixes(asn)]
        direct += [ipaddress.ip_network(p, strict=False) for p in found]
        print(f"мимо VPN, {name}: {len(found)} сетей")
    direct += [widen(n) for n in domain_nets(DIRECT_DOMAINS)]
    direct = list(ipaddress.collapse_addresses(n for n in direct if n.version == 4)) + \
        list(ipaddress.collapse_addresses(n for n in direct if n.version == 6))
    print("мимо VPN:", ", ".join(map(str, direct)))

    nets = [ipaddress.ip_network(n) for n in DNS]
    for name, asns in ASNS.items():
        found = [p for asn in asns for p in asn_prefixes(asn)]
        nets += [ipaddress.ip_network(p, strict=False) for p in found]
        print(f"{name}: {len(found)} сетей")
    goog = json.loads(fetch("https://www.gstatic.com/ipranges/goog.json"))
    nets += [ipaddress.ip_network(p.get("ipv4Prefix") or p["ipv6Prefix"]) for p in goog["prefixes"]]
    for name, url in LISTS.items():
        lines = [l.strip() for l in fetch(url).splitlines() if l.strip() and not l.startswith("#")]
        nets += [ipaddress.ip_network(l, strict=False) for l in lines]
        print(f"{name}: {len(lines)} сетей")
    nets += domain_nets(DOMAINS)

    nets = [widen(n) for n in nets]
    write("routes.txt", exclude(nets, direct))
    write("all.txt", exclude([ipaddress.ip_network("0.0.0.0/0"), ipaddress.ip_network("::/0")], direct))


if __name__ == "__main__":
    main()
