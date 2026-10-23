"""Genera app/src/main/assets/commons_titles.txt: los archivos de logos de canales españoles de Wikimedia Commons.

Recorre la categoría igual que la app (por niveles, hasta 2 de profundidad y 120 categorías) y guarda los títulos
en el mismo orden, para que la app elija los mismos logos que si los buscara ella. Si falla, no toca nada.
"""
import json, sys, time, urllib.parse, urllib.request

API = "https://commons.wikimedia.org/w/api.php"
ROOT = "Category:SVG logos of television channels of Spain"
UA = "MiIPTV/1.0 (https://github.com/jesusvprado-cmd/MI-IPTV; Android TV)"
OUT = "app/src/main/assets/commons_titles.txt"


def get(params):
    url = API + "?format=json&formatversion=2&" + params
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except Exception as e:  # noqa
            time.sleep(2 + attempt * 3)
    raise RuntimeError("Commons no responde")


def main():
    out, seen, level, depth = [], set(), [ROOT], 0
    while level and depth <= 2:
        nxt = []
        for cat in level:
            if cat in seen:
                continue
            seen.add(cat)
            if len(seen) > 120:
                continue
            cont = None
            while True:
                q = "action=query&list=categorymembers&cmtype=file|subcat&cmlimit=500&cmtitle=" + urllib.parse.quote(cat)
                if cont:
                    q += "&cmcontinue=" + urllib.parse.quote(cont)
                o = get(q)
                for m in o.get("query", {}).get("categorymembers", []):
                    if m.get("ns") == 14:
                        nxt.append(m["title"])
                    elif m.get("ns") == 6:
                        out.append(m["title"])
                cont = (o.get("continue") or {}).get("cmcontinue")
                if not cont:
                    break
        level, depth = nxt, depth + 1
    if len(out) < 50:
        raise RuntimeError("Muy pocos logos (%d)" % len(out))
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print("Logos de Commons:", len(out), "archivos en", len(seen), "categorías")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("::warning::No se pudo generar la lista de logos de Commons:", e)
        sys.exit(0)
