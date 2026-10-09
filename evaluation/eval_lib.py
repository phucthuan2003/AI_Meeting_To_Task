"""Shared helpers for the AI quality evaluation (SDS §9.2–9.3). Python stdlib only."""
import json
import re
import unicodedata


def load_dataset(path):
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    for item in data["items"]:
        item["transcript"] = transcript(item)
    return data


def transcript(item):
    if "transcript" in item and item["transcript"]:
        return item["transcript"]
    t = item["transcript_template"]
    lines = list(t["head"]) + [t["filler"].format(i=i) for i in range(t["filler_count"])] + list(t["tail"])
    return "\n".join(lines)


def fold(text):
    """Lowercase, strip diacritics (đ → d) and collapse whitespace for rubric matching."""
    if text is None:
        return ""
    text = text.lower().replace("đ", "d")
    text = unicodedata.normalize("NFD", text)
    text = "".join(c for c in text if unicodedata.category(c) != "Mn")
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9: ]+", " ", text)).strip()


def keyword_score(name, keywords):
    """Share of ground-truth keywords present in the predicted task name (rubric, not the model under test)."""
    if not keywords:
        return 0.0
    target = fold(name)
    return sum(1 for k in keywords if fold(k) in target) / len(keywords)


def match(predicted, truth, threshold=0.5):
    """One-to-one greedy matching by rubric score; evidence overlap breaks ties and can rescue a low keyword score."""
    pairs = []
    for i, p in enumerate(predicted):
        for j, t in enumerate(truth):
            score = keyword_score(p.get("name"), t.get("keywords"))
            overlap = len(set(p.get("evidence_lines") or []) & set(t.get("evidence_lines") or []))
            if score >= threshold or (score > 0 and overlap > 0):
                pairs.append((score + 0.01 * overlap, i, j))
    pairs.sort(reverse=True)
    used_p, used_t, result = set(), set(), []
    for _, i, j in pairs:
        if i in used_p or j in used_t:
            continue
        used_p.add(i)
        used_t.add(j)
        result.append((i, j))
    return result


def same_person(a, b):
    return fold(a) == fold(b)


def same_deadline_raw(a, b):
    if a is None or b is None:
        return a is None and b is None
    fa, fb = fold(a), fold(b)
    return fa == fb or fa in fb or fb in fa
