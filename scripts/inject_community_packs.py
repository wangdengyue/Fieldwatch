import json

def kesc(s):
    return (s.replace('\\', '\\\\').replace('"', '\\"')
             .replace('$', '\\$').replace('\n', ' '))

def rule_kt(r):
    k = r['kind']; radio = r.get('radio'); cid = r.get('companyId', 0); txt = r.get('text', '')
    if k == 'MANUFACTURER_ID': return f"mfg(0x{cid:04X})"
    if k == 'SERVICE_UUID': return f'uuid("{kesc(txt)}")'
    if k == 'OUI': return f'wifiOui("{txt}")' if radio == 'WIFI' else f'oui("{txt}")'
    if k == 'NAME_GLOB':
        if radio == 'BLE': return f'bleGlob("{kesc(txt)}")'
        if radio == 'WIFI': return f'wifiGlob("{kesc(txt)}")'
        return f'glob("{kesc(txt)}")'
    if k == 'NAME_CONTAINS':
        if radio == 'BLE': return f'bleName("{kesc(txt)}")'
        if radio == 'WIFI': return f'wifiName("{kesc(txt)}")'
        return f'name("{kesc(txt)}")'
    raise ValueError(k)

out = []
for path in ['contrib/cn-domestic-signatures.json', 'contrib/public-safety-signatures.json']:
    d = json.load(open(path, encoding='utf-8'))
    for f in d['fleets']:
        rules = ',\n                '.join(rule_kt(r) for r in f['rules'])
        att = ''
        if f.get('attentionNote'):
            att = f'\n            attentionNote = "{kesc(f["attentionNote"])}",'
        out.append(
            '        Fleet(\n'
            f'            id = "{f["id"]}",\n'
            f'            name = "{kesc(f["name"])}",\n'
            f'            colorIndex = {f["colorIndex"]},\n'
            f'            kind = SignatureClass.{f["kind"]},\n'
            '            matchAny = true,\n'
            f'            notes = "{kesc(f["notes"])}",{att}\n'
            '            builtIn = true,\n'
            '            rules = listOf(\n'
            f'                {rules},\n'
            '            ),\n'
            '        ),'
        )

block = (
    '\n    // ---- Community additions (local packs: CN domestic + RFSentinel public-safety).\n'
    '    // ---- Compiled in on this fork; drop communityPackFleets() from fleets() to revert.\n'
    '    private fun communityPackFleets(): List<Fleet> = listOf(\n'
    + '\n'.join(out) +
    '\n    )\n\n'
)

p = 'app/src/main/java/app/fieldwatch/domain/DefaultCatalog.kt'
src = open(p, encoding='utf-8').read()
anchor = '    private fun oui(prefix: String) = MatchRule(RuleKind.OUI, text = prefix)'
assert src.count(anchor) == 1
src = src.replace(anchor, block + anchor, 1)
old = '    fun fleets(): List<Fleet> = listOf('
assert src.count(old) == 1
src = src.replace(old, '    fun fleets(): List<Fleet> = communityPackFleets() + listOf(', 1)
open(p, 'w', encoding='utf-8', newline='\n').write(src)
print('injected fleets:', len(out))
