"""Extract a deterministic subset of FLEURS vi_vn/test for a paired benchmark."""
import argparse
import csv
import json
from pathlib import Path
import tarfile


def prepare(archive: Path, labels: Path, output: Path, limit: int) -> None:
    with labels.open(encoding="utf-8") as stream:
        rows = {row[1]: row for row in csv.reader(stream, delimiter="\t")}
    output.mkdir(parents=True, exist_ok=True)
    selected = []
    seen = {"1738"}  # Exclude the project's earlier smoke fixture.
    with tarfile.open(archive, "r|gz") as bundle:
        for entry in bundle:
            filename = Path(entry.name).name
            if not entry.isfile() or filename not in rows:
                continue
            id_, _, transcript, _, _, samples, gender = rows[filename]
            if id_ in seen or not 96_000 <= int(samples) <= 256_000:
                continue
            with bundle.extractfile(entry) as member:
                (output / filename).write_bytes(member.read())
            selected.append({"file": filename, "transcript": transcript, "id": id_, "gender": gender})
            seen.add(id_)
            if len(selected) == limit:
                break
    if len(selected) != limit:
        raise ValueError(f"Only {len(selected)} of {limit} requested clips were available")
    (output / "ground_truth.json").write_text(
        json.dumps(selected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Prepared {len(selected)} distinct FLEURS test utterances in {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("labels", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--limit", type=int, default=24, choices=range(1, 101), metavar="1–100")
    args = parser.parse_args()
    prepare(args.archive, args.labels, args.output, args.limit)
