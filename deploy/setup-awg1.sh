#!/usr/bin/env bash
# Поднимает отдельный интерфейс AmneziaWG awg1 для друзей: свой порт, своя подсеть, свои параметры обфускации.
# Личный awg0 не трогает. Повторный запуск безопасен: существующий конфиг не перезаписывается.
# В конце печатает параметры для /opt/awgbot/server.env.
set -euo pipefail

IF=awg1
PORT=${PORT:-47213}
ENDPOINT_HOST=${ENDPOINT_HOST:-$(curl -4 -s https://ifconfig.me)}
DIR=/etc/amnezia/amneziawg
CONF=$DIR/$IF.conf
WAN=$(ip -4 route show default | awk '{print $5; exit}')

if [ ! -f "$CONF" ]; then
    umask 077
    priv=$(awg genkey)
    rnd() { shuf -i "$1-$2" -n 1; }
    jc=$(rnd 4 10)
    s1=$(rnd 15 120)
    s2=$(rnd 15 120)
    # при S1 + 56 == S2 пакеты рукопожатия и ответа становятся одной длины - так нельзя
    while [ $((s1 + 56)) -eq "$s2" ]; do s2=$(rnd 15 120); done
    read -r h1 h2 h3 h4 <<< "$(shuf -i 5-2147483647 -n 4 | tr '\n' ' ')"

    up="iptables -I FORWARD 1 -i %i -o %i -j DROP; iptables -I FORWARD 1 -i %i -o awg0 -j DROP; iptables -I FORWARD 1 -i %i -p tcp --dport 25 -j DROP"
    up="$up; iptables -A FORWARD -i %i -o $WAN -j ACCEPT; iptables -A FORWARD -i $WAN -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT"
    up="$up; iptables -t nat -A POSTROUTING -s 10.9.0.0/24 -o $WAN -j MASQUERADE"
    up="$up; ip6tables -I FORWARD 1 -i %i -o %i -j DROP; ip6tables -A FORWARD -i %i -o $WAN -j ACCEPT"
    up="$up; ip6tables -A FORWARD -i $WAN -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT"
    up="$up; ip6tables -t nat -A POSTROUTING -s fd09:9::/64 -o $WAN -j MASQUERADE"
    down=$(echo "$up" | sed -E 's/ -[IA] (FORWARD|POSTROUTING)( 1)?/ -D \1/g')

    cat > "$CONF" <<CONF
[Interface]
Address = 10.9.0.1/24, fd09:9::1/64
ListenPort = $PORT
PrivateKey = $priv
Jc = $jc
Jmin = 40
Jmax = 70
S1 = $s1
S2 = $s2
H1 = $h1
H2 = $h2
H3 = $h3
H4 = $h4
PostUp = $up
PostDown = $down
CONF
    echo "Создан $CONF"
fi

# IPv6 у VPS прописан статически (accept_ra=0), так что включение форвардинга маршрут не сломает
echo "net.ipv6.conf.all.forwarding = 1" > /etc/sysctl.d/99-awg1.conf
sysctl -q -p /etc/sysctl.d/99-awg1.conf
systemctl enable --now "awg-quick@$IF"

port=$(awk -F' = ' '$1=="ListenPort"{print $2}' "$CONF")
obf=$(awk -F' = ' '$1 ~ /^(Jc|Jmin|Jmax|S1|S2|H1|H2|H3|H4)$/ {printf "%s%s=%s", sep, $1, $2; sep=";"}' "$CONF")
cat <<ENV

# ---- параметры для /opt/awgbot/server.env ----
AWG_ENDPOINT=$ENDPOINT_HOST:$port
AWG_SERVER_PUBLIC_KEY=$(awg show "$IF" public-key)
AWG_OBFUSCATION="$obf"
ENV
