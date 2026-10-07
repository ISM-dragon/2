#!/usr/bin/env python3
"""Room data layer guard.

This tool is the executable part of the data layer test suite that does not need a JVM. It:

  1. parses every ``@Entity`` declaration of the Room data layer (name, table, columns, nullability,
     primary keys, indices, foreign keys);
  2. parses the historical (git HEAD = schema v2) entities the same way;
  3. generates the exact DDL Room would generate for a schema version;
  4. validates the hand written migration SQL of ``DatabaseMigrations`` against the entities:
       * schema v1 -> v2 normalizer must reproduce the historical v2 shape;
       * schema v2 -> v3 migration must end in a schema that is byte-for-byte equivalent
         (via PRAGMA inspection, the same way Room's TableInfo.read does it) to the current
         entity declarations, including indices and foreign keys;
  5. applies the migration to a real SQLite database pre-filled with representative v2 data and
     checks data preservation, ``PRAGMA foreign_key_check`` integrity and the dedup behaviours
     (unique indices, cascade deletes);
  6. prepares every ``@Query`` SQL of every DAO against the migrated database so that typos in
     table/column names are caught the same way the Room compiler would catch them.

Run:  python3 tools/room_schema_guard.py            # full check
      python3 tools/room_schema_guard.py --print-ddl v3
"""

from __future__ import annotations

import argparse
import re
import sqlite3
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENTITY_DIR = "app/src/main/java/com/example/data/local/entity"
DAO_DIR = "app/src/main/java/com/example/data/local/dao"
MIGRATION_DIR = "app/src/main/java/com/example/data/local/migration"

# ────────────────────────────────────────────────────────────────────────────────────────────────
# Kotlin source scanning helpers
# ────────────────────────────────────────────────────────────────────────────────────────────────


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def git_show(path: str, rev: str = "HEAD") -> str:
    out = subprocess.run(
        ["git", "show", f"{rev}:{path}"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    return out.stdout


def match_bracket(text: str, open_idx: int, opener: str = "(", closer: str = ")") -> int:
    """Return the index of the closer matching the opener at ``open_idx``."""
    depth = 0
    i = open_idx
    in_str = None
    while i < len(text):
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if text.startswith(in_str, i):
                i += len(in_str)
                in_str = None
                continue
        else:
            if text.startswith('"""', i):
                in_str = '"""'
                i += 3
                continue
            if ch == '"':
                in_str = '"'
            elif ch == opener:
                depth += 1
            elif ch == closer:
                depth -= 1
                if depth == 0:
                    return i
        i += 1
    raise ValueError("unbalanced brackets")


def strip_kotlin_comments(text: str) -> str:
    """Remove // and /* */ comments while leaving string literals untouched."""
    out: list[str] = []
    i = 0
    while i < len(text):
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = len(text) if end == -1 else end + 3
            out.append(text[i:end])
            i = end
            continue
        ch = text[i]
        if ch in "\"'":
            j = i + 1
            while j < len(text):
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == ch:
                    break
                j += 1
            out.append(text[i : j + 1])
            i = j + 1
            continue
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = len(text) if end == -1 else end
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i)
            i = len(text) if end == -1 else end + 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def split_top_level(text: str, sep: str = ",") -> list[str]:
    parts, depth, i, start = [], 0, 0, 0
    in_str = None
    while i < len(text):
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if text.startswith(in_str, i):
                i += len(in_str)
                in_str = None
                continue
        elif text.startswith('"""', i):
            in_str = '"""'
            i += 3
            continue
        elif ch == '"':
            in_str = '"'
        elif ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        elif ch == sep and depth == 0:
            parts.append(text[start:i])
            start = i + 1
        i += 1
    parts.append(text[start:])
    return [p for p in parts if p.strip()]


def kotlin_strings(text: str) -> list[str]:
    """Extract triple quoted and simple string literals (including ``+`` concatenation)."""
    results, i = [], 0
    while i < len(text):
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            if end == -1:
                raise ValueError("unterminated raw string")
            results.append(text[i + 3 : end])
            i = end + 3
            continue
        if text[i] == '"' and not text.startswith('"""', i):
            j, buf = i + 1, []
            while j < len(text):
                if text[j] == "\\":
                    nxt = text[j + 1]
                    buf.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\", "$": "$"}.get(nxt, nxt))
                    j += 2
                    continue
                if text[j] == '"':
                    break
                buf.append(text[j])
                j += 1
            results.append("".join(buf))
            i = j + 1
            continue
        i += 1
    return results


# ────────────────────────────────────────────────────────────────────────────────────────────────
# Entity model
# ────────────────────────────────────────────────────────────────────────────────────────────────

AFFINITY = {
    "String": "TEXT",
    "Int": "INTEGER",
    "Long": "INTEGER",
    "Boolean": "INTEGER",
    "Double": "REAL",
    "Float": "REAL",
    "ByteArray": "BLOB",
}


@dataclass
class Column:
    name: str
    affinity: str
    not_null: bool
    pk_position: int = 0
    auto_generate: bool = False
    default: str | None = None


@dataclass
class Index:
    name: str
    columns: list[str]
    unique: bool


@dataclass
class ForeignKey:
    child_columns: list[str]
    parent_table: str
    parent_columns: list[str]
    on_delete: str = "NO ACTION"
    on_update: str = "NO ACTION"


@dataclass
class Table:
    entity: str
    name: str
    columns: list[Column]
    indices: list[Index] = field(default_factory=list)
    foreign_keys: list[ForeignKey] = field(default_factory=list)

    def column(self, name: str) -> Column | None:
        return next((c for c in self.columns if c.name == name), None)


def parse_entities(files: dict[str, str]) -> dict[str, Table]:
    tables: dict[str, Table] = {}
    entity_table_by_class: dict[str, str] = {}
    raw: list[tuple[str, str, str]] = []  # (class, table, body)

    for raw_source in files.values():
        source = strip_kotlin_comments(raw_source)
        for m in re.finditer(r"@Entity\s*\(", source):
            ann_open = source.index("(", m.start())
            ann_close = match_bracket(source, ann_open)
            annotation = source[ann_open + 1 : ann_close]
            rest = source[ann_close:]
            dm = re.search(r"data class\s+(\w+)\s*\(", rest)
            if not dm:
                continue
            params_open = ann_close + dm.end() - 1
            params_close = match_bracket(source, params_open)
            params_body = source[params_open + 1 : params_close]
            name_m = re.search(r'tableName\s*=\s*"([^"]+)"', annotation)
            cls = dm.group(1)
            table = name_m.group(1) if name_m else cls
            entity_table_by_class[cls] = table
            raw.append((cls, table, annotation + "\u0000" + params_body))

    for cls, table, body in raw:
        annotation, params_body = body.split("\u0000", 1)
        columns: list[Column] = []
        for param in split_top_level(params_body):
            param = param.strip()
            if not param or param.startswith("//"):
                continue
            auto = "autoGenerate = true" in param
            column_info = re.search(r'@ColumnInfo\s*\(([^)]*)\)', param)
            explicit_name = None
            default = None
            if column_info:
                nm = re.search(r'name\s*=\s*"([^"]+)"', column_info.group(1))
                if nm:
                    explicit_name = nm.group(1)
                dm = re.search(r'defaultValue\s*=\s*"([^"]*)"', column_info.group(1))
                if dm:
                    default = dm.group(1)
            decl = re.search(r"val\s+(\w+)\s*:\s*([\w<>.? ]+)", param)
            if not decl:
                continue
            name, ktype = decl.group(1), decl.group(2).strip()
            nullable = ktype.endswith("?")
            base = ktype.rstrip("?")
            affinity = AFFINITY.get(base)
            if affinity is None:
                raise ValueError(f"{cls}: unsupported Room column type {ktype}")
            is_pk = "@PrimaryKey" in param
            columns.append(
                Column(
                    name=explicit_name or name,
                    affinity=affinity,
                    not_null=not nullable,
                    pk_position=1 if is_pk else 0,
                    auto_generate=auto,
                    default=default,
                )
            )

        table_obj = Table(entity=cls, name=table, columns=columns)

        indices_body = re.search(r"indices\s*=\s*\[", annotation)
        if indices_body:
            open_idx = annotation.index("[", indices_body.start())
            close_idx = match_bracket(annotation, open_idx, "[", "]")
            for item in split_top_level(annotation[open_idx + 1 : close_idx]):
                item = item.strip()
                if not item:
                    continue
                val = re.search(r'value\s*=\s*\[([^\]]*)\]', item)
                if not val:
                    continue
                cols = re.findall(r'"([^"]+)"', val.group(1))
                unique = bool(re.search(r"unique\s*=\s*true", item))
                name_m = re.search(r'name\s*=\s*"([^"]+)"', item)
                index_name = name_m.group(1) if name_m else f"index_{table}_{'_'.join(cols)}"
                table_obj.indices.append(Index(index_name, cols, unique))

        fks_body = re.search(r"foreignKeys\s*=\s*\[", annotation)
        if fks_body:
            open_idx = annotation.index("[", fks_body.start())
            close_idx = match_bracket(annotation, open_idx, "[", "]")
            for item in split_top_level(annotation[open_idx + 1 : close_idx]):
                if "ForeignKey" not in item:
                    continue
                ent = re.search(r"entity\s*=\s*(\w+)::class", item)
                parent = re.search(r'parentColumns\s*=\s*\[([^\]]*)\]', item)
                child = re.search(r'childColumns\s*=\s*\[([^\]]*)\]', item)
                on_del = re.search(r"onDelete\s*=\s*ForeignKey\.(\w+)", item)
                on_upd = re.search(r"onUpdate\s*=\s*ForeignKey\.(\w+)", item)
                table_obj.foreign_keys.append(
                    ForeignKey(
                        child_columns=re.findall(r'"([^"]+)"', child.group(1)),
                        parent_table="",  # resolved later
                        parent_columns=re.findall(r'"([^"]+)"', parent.group(1)),
                        on_delete=on_del.group(1).replace("_", " ") if on_del else "NO ACTION",
                        on_update=on_upd.group(1).replace("_", " ") if on_upd else "NO ACTION",
                    )
                )
                table_obj.foreign_keys[-1].parent_table = ent.group(1)
        tables[table] = table_obj

    for table in tables.values():
        for fk in table.foreign_keys:
            fk.parent_table = entity_table_by_class[fk.parent_table]
    return tables


def room_ddl(table: Table) -> list[str]:
    """Room-style DDL for a table (used to print reference SQL)."""
    cols = []
    for c in table.columns:
        if c.pk_position and c.auto_generate:
            cols.append(f"`{c.name}` {c.affinity} PRIMARY KEY AUTOINCREMENT{' NOT NULL' if c.not_null else ''}")
            continue
        piece = f"`{c.name}` {c.affinity}"
        if c.default is not None:
            piece += f" DEFAULT {c.default}"
        if c.not_null:
            piece += " NOT NULL"
        cols.append(piece)
    if any(c.pk_position and not c.auto_generate for c in table.columns):
        pk_cols = ", ".join(f"`{c.name}`" for c in table.columns if c.pk_position)
        cols.append(f"PRIMARY KEY({pk_cols})")
    for fk in table.foreign_keys:
        clause = (
            f"FOREIGN KEY({', '.join(f'`{c}`' for c in fk.child_columns)}) "
            f"REFERENCES `{fk.parent_table}`({', '.join(f'`{c}`' for c in fk.parent_columns)})"
        )
        if fk.on_update != "NO ACTION" or fk.on_delete != "NO ACTION":
            clause += " ON UPDATE " + fk.on_update
            clause += " ON DELETE " + fk.on_delete
        cols.append(clause)
    stmts = [f"CREATE TABLE IF NOT EXISTS `{table.name}` ({', '.join(cols)})"]
    for idx in table.indices:
        unique = "UNIQUE " if idx.unique else ""
        stmts.append(
            f"CREATE {unique}INDEX IF NOT EXISTS `{idx.name}` ON `{table.name}` "
            f"({', '.join(f'`{c}`' for c in idx.columns)})"
        )
    return stmts


# ────────────────────────────────────────────────────────────────────────────────────────────────
# Migration parsing
# ────────────────────────────────────────────────────────────────────────────────────────────────


def migration_statements(kotlin_source: str, migration_class: str, strict: bool = True) -> list[str]:
    """Extract the ordered ``execSQL`` literals of one Migration class.

    ``strict`` requires every execSQL call to be a literal (used for the fully static v2 -> v3
    migration). The v1 -> v2 normalizer is allowed to build SQL dynamically, its shape contract is
    validated through the ``LegacyV2Schema`` catalog instead.
    """
    start = kotlin_source.index(f"class Migration{migration_class}")
    body_start = kotlin_source.index("migrate", start)
    # take everything up to the next migration class or end of file
    following = re.search(r"\n(?:private|internal) class Migration", kotlin_source[body_start:])
    body = kotlin_source[body_start : body_start + following.start()] if following else kotlin_source[body_start:]
    statements = []
    for m in re.finditer(r"execSQL\s*\(", body):
        open_idx = body.index("(", m.start())
        close_idx = match_bracket(body, open_idx)
        literals = kotlin_strings(body[open_idx + 1 : close_idx])
        if not literals:
            if strict:
                raise ValueError(
                    f"non literal SQL in Migration{migration_class}: {body[m.start():m.start() + 80]}"
                )
            continue
        statements.append("".join(literals).strip())
    return statements


def ddl_literals(kotlin_source: str, object_name: str) -> dict[str, str]:
    """Map table/index name -> DDL string for the literals declared in a Kotlin object."""
    start = kotlin_source.index(f"object {object_name}")
    brace = kotlin_source.index("{", start)
    end = kotlin_source.index("\n}\n", brace)
    body = kotlin_source[brace:end]
    result: dict[str, str] = {}
    for sql in kotlin_strings(body):
        sql = sql.strip()
        upper = sql.upper()
        if upper.startswith("CREATE TABLE"):
            name = re.search(r"`([^`]+)`", sql)
            if name:
                result[name.group(1)] = sql
        elif upper.startswith("CREATE INDEX") or upper.startswith("CREATE UNIQUE INDEX"):
            name = re.search(r"INDEX(?: IF NOT EXISTS)? `([^`]+)`", sql)
            if name:
                result[name.group(1)] = sql
    return result


# ────────────────────────────────────────────────────────────────────────────────────────────────
# PRAGMA based schema inspection (mirrors what Room's TableInfo.read validates)
# ────────────────────────────────────────────────────────────────────────────────────────────────


@dataclass
class ActualColumn:
    name: str
    type: str
    not_null: bool
    pk: int
    default: str | None


def actual_tables(conn: sqlite3.Connection) -> set[str]:
    rows = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    ).fetchall()
    return {r[0] for r in rows}


def actual_columns(conn: sqlite3.Connection, table: str) -> dict[str, ActualColumn]:
    out: dict[str, ActualColumn] = {}
    for _, name, ctype, notnull, dflt, pk in conn.execute(f"PRAGMA table_info(`{table}`)"):
        out[name] = ActualColumn(name, ctype or "", bool(notnull), int(pk), dflt)
    return out


def actual_indices(conn: sqlite3.Connection, table: str) -> dict[str, Index]:
    out: dict[str, Index] = {}
    for row in conn.execute(f"PRAGMA index_list(`{table}`)"):
        name, unique = row[1], bool(row[2])
        origin = row[3] if len(row) > 3 else "c"
        if name.startswith("sqlite_autoindex") or origin == "pk":
            continue
        cols = [r[2] for r in conn.execute(f"PRAGMA index_info(`{name}`)")]
        out[name] = Index(name, cols, unique)
    return out


def actual_foreign_keys(conn: sqlite3.Connection, table: str) -> set[tuple]:
    fks = set()
    for row in conn.execute(f"PRAGMA foreign_key_list(`{table}`)"):
        # id, seq, table, from, to, on_update, on_delete, match
        fks.add(
            (
                row[3],
                row[2],
                row[4],
                (row[6] or "NO ACTION").upper(),
                (row[5] or "NO ACTION").upper(),
            )
        )
    return fks


def expected_foreign_keys(table: Table) -> set[tuple]:
    return {
        (
            fk.child_columns[0],
            fk.parent_table,
            fk.parent_columns[0],
            fk.on_delete.upper(),
            fk.on_update.upper(),
        )
        for fk in table.foreign_keys
    }


def affinity_of(declared: str) -> str:
    t = declared.upper()
    if "INT" in t:
        return "INTEGER"
    if any(x in t for x in ("CHAR", "CLOB", "TEXT")):
        return "TEXT"
    if "BLOB" in t or t == "":
        return "BLOB"
    if any(x in t for x in ("REAL", "FLOA", "DOUB")):
        return "REAL"
    return "NUMERIC"


def normalize_default(value: str | None) -> str | None:
    if value is None:
        return None
    v = value.strip()
    if v.startswith("(") and v.endswith(")") and not v.startswith("(("):
        v = v[1:-1].strip()
    if v.upper() == "NULL":
        return None
    try:
        return str(int(v))
    except ValueError:
        pass
    try:
        return str(float(v))
    except ValueError:
        pass
    if len(v) >= 2 and v[0] == "'" and v[-1] == "'":
        v = v[1:-1]
    return v


def compare_table(entity: Table, conn: sqlite3.Connection) -> list[str]:
    """Room equivalent schema validation of one entity against the live database."""
    problems: list[str] = []
    cols = actual_columns(conn, entity.name)
    if not cols:
        return [f"[{entity.name}] table is missing"]
    table_sql = (
        conn.execute("SELECT sql FROM sqlite_master WHERE name=?", (entity.name,)).fetchone() or [""]
    )[0] or ""
    for column in entity.columns:
        actual = cols.get(column.name)
        if actual is None:
            problems.append(f"[{entity.name}] column `{column.name}` is missing")
            continue
        if affinity_of(actual.type) != column.affinity:
            problems.append(
                f"[{entity.name}.{column.name}] affinity {affinity_of(actual.type)} != {column.affinity}"
            )
        if actual.not_null != column.not_null:
            problems.append(
                f"[{entity.name}.{column.name}] notNull {actual.not_null} != {column.not_null}"
            )
        if (actual.pk > 0) != (column.pk_position > 0):
            problems.append(
                f"[{entity.name}.{column.name}] primary key mismatch "
                f"(db pk={actual.pk}, entity pk={column.pk_position})"
            )
        if column.auto_generate and "AUTOINCREMENT" not in table_sql.upper():
            problems.append(f"[{entity.name}.{column.name}] AUTOINCREMENT missing")
        expected_default = normalize_default(column.default)
        actual_default = normalize_default(actual.default) if actual.default is not None else None
        if expected_default is not None and expected_default != actual_default:
            problems.append(
                f"[{entity.name}.{column.name}] default {actual_default!r} != {expected_default!r}"
            )
    extra = set(cols) - {c.name for c in entity.columns}
    if extra:
        problems.append(f"[{entity.name}] unexpected columns in database: {sorted(extra)}")

    db_indices = actual_indices(conn, entity.name)
    expected_indices = {i.name: (tuple(i.columns), i.unique) for i in entity.indices}
    for name, (index_cols, unique) in expected_indices.items():
        if name not in db_indices:
            problems.append(f"[{entity.name}] index `{name}` is missing")
            continue
        actual_index = db_indices[name]
        if tuple(actual_index.columns) != index_cols:
            problems.append(
                f"[{entity.name}] index `{name}` columns {actual_index.columns} != {list(index_cols)}"
            )
        if actual_index.unique != unique:
            problems.append(
                f"[{entity.name}] index `{name}` unique {actual_index.unique} != {unique}"
            )
    for name in set(db_indices) - set(expected_indices):
        problems.append(f"[{entity.name}] unexpected index in database: `{name}`")

    db_fks = actual_foreign_keys(conn, entity.name)
    expected_fks = expected_foreign_keys(entity)
    for fk in sorted(expected_fks - db_fks):
        problems.append(f"[{entity.name}] foreign key missing: {fk}")
    for fk in sorted(db_fks - expected_fks):
        problems.append(f"[{entity.name}] unexpected foreign key: {fk}")
    return problems


# ────────────────────────────────────────────────────────────────────────────────────────────────
# DAO query validation
# ────────────────────────────────────────────────────────────────────────────────────────────────


def dao_queries() -> dict[str, list[str]]:
    queries: dict[str, list[str]] = {}
    for path in sorted((ROOT / DAO_DIR).glob("*.kt")):
        source = strip_kotlin_comments(path.read_text(encoding="utf-8"))
        found = []
        for m in re.finditer(r"@Query\s*\(", source):
            open_idx = source.index("(", m.start())
            close_idx = match_bracket(source, open_idx)
            found.append("".join(kotlin_strings(source[open_idx + 1 : close_idx])).strip())
        queries[path.name] = found
    return queries


def check_queries(conn: sqlite3.Connection, queries: dict[str, list[str]]) -> list[str]:
    problems = []
    for file, statements in queries.items():
        for sql in statements:
            prepared = re.sub(r":\w+", "NULL", sql)
            try:
                conn.execute("EXPLAIN " + prepared)
            except sqlite3.Error as exc:
                problems.append(f"[{file}] {exc}\n    SQL: {sql}")
    return problems


# ────────────────────────────────────────────────────────────────────────────────────────────────
# Scenario data for the v2 -> v3 migration test
# ────────────────────────────────────────────────────────────────────────────────────────────────

V2_SEED_ROWS = [
    # propertyId, sourceType, address, city, state, zip, price, isSaved, dealScore, scannedAt
    ("prop-old-1", "ON_MARKET", "2418 S Congress Ave", "Austin", "TX", "78704", 485000.0, 1, 88, 1_700_000_000_000),
    ("prop-old-2", "OFF_MARKET", "1820 E Thomas Rd", "Phoenix", "AZ", "85016", 540000.0, 0, 92, 1_700_000_100_000),
    ("prop-old-3", "ON_MARKET", "500 Congress Ave", "Austin", "TX", "78701", 550000.0, 0, 40, 1_700_000_200_000),
]

PROPERTY_V2_COLUMNS = [
    "id", "sourceType", "title", "address", "city", "state", "zipCode", "latitude", "longitude",
    "price", "propertyType", "bedrooms", "bathrooms", "squareFeet", "yearBuilt", "lotSizeSqFt",
    "description", "status", "primaryImageUrl", "scannedAt", "isSaved", "isSavedDeal", "dealScore",
]


def seed_v2_data(conn: sqlite3.Connection) -> None:
    """Insert representative v2 rows using the historical (git HEAD) schema."""
    for pid, source, address, city, state, zip_code, price, saved, score, scanned in V2_SEED_ROWS:
        conn.execute(
            f"INSERT INTO properties ({', '.join(PROPERTY_V2_COLUMNS)}) VALUES "
            "(?, ?, ?, ?, ?, ?, ?, 30.25, -97.75, ?, 'Single Family', 3, 2.0, 1750, 2015, 5500, "
            "'seed', 'Active', '', ?, ?, 0, ?)",
            (pid, source, f"{address} home", address, city, state, zip_code, price, scanned, saved, score),
        )
        conn.execute(
            "INSERT INTO property_images (propertyId, imageUrl, caption, isPrimary) VALUES (?, 'u', 'c', 1)",
            (pid,),
        )
        conn.execute(
            "INSERT INTO market_data (propertyId, estimatedValue, neighborhoodAppreciationRate, "
            "medianAreaPrice, averageDaysOnMarket, pricePerSqFt, marketDemand) "
            "VALUES (?, ?, 5.0, ?, 20, ?, 'High')",
            (pid, price * 1.05, price, price / 1750),
        )
        conn.execute(
            "INSERT INTO rent_estimates (propertyId, estimatedRent, rentRangeLow, rentRangeHigh, "
            "rentConfidenceScore, grossYield) VALUES (?, ?, ?, ?, 90.0, 8.0)",
            (pid, 3800.0, 3500.0, 4100.0),
        )
        conn.execute(
            "INSERT INTO tax_records (propertyId, annualTaxAmount, assessmentYear, assessedValue, "
            "taxDelinquent) VALUES (?, ?, 2025, ?, 0)",
            (pid, 5800.0, price * 0.85),
        )
        conn.execute(
            "INSERT INTO sales_history (propertyId, date, price, event) VALUES (?, '2024-01-01', ?, 'Sold')",
            (pid, price * 0.9),
        )
        conn.execute(
            "INSERT INTO financial_analyses (propertyId, purchasePrice, closingCosts, renovationCost, "
            "monthlyRent, otherMonthlyIncome, vacancyRatePct, propertyTaxAnnual, insuranceAnnual, "
            "maintenancePct, managementPct, utilitiesMonthly, downPaymentPct, interestRatePct, "
            "loanTermYears, grossRentalIncome, effectiveRentalIncome, operatingExpensesMonthly, "
            "noiAnnual, monthlyDebtService, monthlyCashFlow, annualCashFlow, capRate, "
            "cashOnCashReturn, dscr, breakEvenOccupancyPct, totalCashRequired, calculatedAt, "
            "isQualified, dealScore, qualificationSummary) VALUES "
            "(?, ?, 8000, 5000, ?, 0, 5, 5800, 1800, 5, 8, 0, 20, 6.8, 30, ?, ?, 1200, 30000, "
            "2400, 300, 3600, 6.2, 8.5, 1.3, 65, 120000, 1, 1, 90, 'ok')",
            (pid, price, 3800.0, 45600.0, 42000.0),
        )
        conn.execute(
            "INSERT INTO financing_scenarios (propertyId, scenarioName, downPaymentPct, "
            "interestRatePct, loanTermYears, monthlyPayment, cashRequired, monthlyCashFlow, "
            "cashOnCash, dscr) VALUES (?, 'Conventional', 20, 6.8, 30, 2400, 120000, 300, 8.5, 1.3)",
            (pid,),
        )
    # legacy comps, including two rows that must dedup into a single canonical comp
    conn.execute(
        "INSERT INTO comparable_properties (targetPropertyId, compAddress, compPrice, compBeds, "
        "compBaths, compSqFt, distanceMiles, saleDate, adjustmentAmount) VALUES "
        "('prop-old-1', '2402 S Congress Ave', 515000, 4, 3.0, 2300, 0.1, '2025-11-01', 0),"
        "('prop-old-1', '2402 S Congress Ave', 515000, 4, 3.0, 2300, 0.1, '2025-11-01', 0),"
        "('prop-old-1', '310 Elizabeth St', 495000, 3, 2.5, 2100, 0.3, '2026-01-10', 0),"
        "('prop-old-2', '1800 E Thomas Rd', 520000, 8, 4.0, 3500, 0.2, '2025-12-05', 0)"
    )
    conn.execute(
        "INSERT INTO saved_properties (propertyId, savedAt, notes, tag) VALUES ('prop-old-1', 1, '', 'Watchlist')"
    )
    conn.execute(
        "INSERT INTO saved_deals (dealId, propertyId, qualificationReason, targetOfferPrice, "
        "expectedRoi, savedAt) VALUES ('deal-1', 'prop-old-1', 'cash flow', 430000, 12.0, 1)"
    )
    conn.execute(
        "INSERT INTO ai_conversations (id, propertyId, title, createdAt, updatedAt) "
        "VALUES ('conv-1', 'prop-old-1', 'Deal review', 1, 1), ('conv-2', NULL, 'General', 1, 1)"
    )
    conn.execute(
        "INSERT INTO ai_messages (conversationId, role, content, timestamp) VALUES ('conv-1', 'user', 'hi', 1)"
    )
    conn.execute(
        "INSERT INTO offers (id, propertyId, recipientName, recipientEmail, offerPrice, earnestMoney, "
        "inspectionPeriodDays, closingPeriodDays, contingencies, terms, conditions, expirationDate, "
        "generatedLetterContent, pdfPath, status, createdAt, sentAt, lastError) VALUES "
        "('offer-1', 'prop-old-1', 'Agent', 'a@b.com', 430000, 5000, 10, 21, '', '', '', '', '', NULL, "
        "'READY', 1, NULL, NULL)"
    )
    conn.execute(
        "INSERT INTO offer_documents (offerId, fileName, filePath, fileSizeBytes, createdAt) "
        "VALUES ('offer-1', 'f.pdf', '/tmp/f.pdf', 100, 1)"
    )
    conn.execute(
        "INSERT INTO automation_runs (startTime, endTime, propertiesFound, propertiesAnalyzed, "
        "dealsQualified, offersCreated, offersSent, status, summary) VALUES (1, 2, 3, 2, 1, 1, 0, 'COMPLETED', '')"
    )
    conn.execute(
        "INSERT INTO automation_logs (runId, timestamp, level, tag, message) VALUES (1, 1, 'INFO', 'T', 'm')"
    )
    conn.execute(
        "INSERT INTO automation_state (id, isEnabled, currentOperation, currentPropertyAddress, "
        "currentStage, successfulJobs, failedJobs, lastError, lastSuccessfulAction, lastActivityTime) "
        "VALUES (1, 0, 'Standby', '', 'Standby', 0, 0, NULL, 'None', 0)"
    )
    conn.execute(
        "INSERT INTO automation_jobs (jobId, runId, propertyId, propertyAddress, currentState, "
        "lastSuccessfulState, failedStep, analysisId, offerId, emailMessageId, recipientEmail, "
        "attempts, maxRetries, lastError, blockageReason, createdAt, updatedAt) VALUES "
        "('JOB-1', 1, 'prop-old-1', 'x', 'SENT', 'SENT', NULL, NULL, NULL, NULL, NULL, 1, 3, NULL, NULL, 1, 1)"
    )
    conn.execute(
        "INSERT INTO automation_rules (id, maxPurchasePrice, minCashFlow, minCapRate, minDscr, "
        "minCashOnCash, allowedLocations, allowedPropertyTypes, maxRenovationCost, minEstimatedRent, "
        "maxRiskScore, offerDiscountPercent, scanIntervalMinutes, maxPropertiesPerCycle, "
        "maxAnalysesPerRun, maxOffersPerRun, maxEmailsPerRun, maxRetries, autoGenerateOffers, "
        "autoSendOffers, consecutiveFailureThreshold) VALUES ('DEFAULT', 600000, 300, 7, 1.25, 8, "
        "'Austin', 'Single Family', 75000, 1500, 40, 8.5, 15, 10, 5, 3, 3, 3, 1, 0, 3)"
    )
    conn.execute(
        "INSERT INTO api_configurations (slotIndex, label, apiKey, model, projectId, status, "
        "lastRequestTime, cooldownUntil, usageCount, errorCount, lastErrorMessage) "
        "VALUES (1, 'slot', 'k', 'gemini', '', 'READY', 0, 0, 0, 0, NULL)"
    )
    conn.execute(
        "INSERT INTO gmail_configuration (id, isConnected, authStatus, accountEmail, senderName, "
        "signature, defaultSubjectTemplate, defaultCc, accessToken, refreshToken, expiresAt, lastError) "
        "VALUES (1, 0, 'NOT_CONFIGURED', '', '', '', '', '', NULL, NULL, 0, NULL)"
    )
    conn.execute(
        "INSERT INTO offer_templates (id, templateName, headerTitle, earnestMoneyPercent, "
        "defaultInspectionDays, defaultClosingDays, standardTerms, standardConditions) "
        "VALUES ('DEFAULT', 't', 'h', 1.5, 10, 21, '', '')"
    )
    conn.commit()


# ────────────────────────────────────────────────────────────────────────────────────────────────
# Main
# ────────────────────────────────────────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--print-ddl", choices=["v2", "v3"])
    parser.add_argument("--print-shapes", choices=["v2"], help="emit LegacyTableShape literals")
    args = parser.parse_args()

    current_entities = {
        p.name: p.read_text(encoding="utf-8") for p in sorted((ROOT / ENTITY_DIR).glob("*.kt"))
    }
    head_entities = {}
    for name in current_entities:
        try:
            head_entities[name] = git_show(f"{ENTITY_DIR}/{name}")
        except subprocess.CalledProcessError:
            continue

    v3 = parse_entities(current_entities)
    v2 = parse_entities(head_entities)

    if args.print_ddl:
        target = v2 if args.print_ddl == "v2" else v3
        for table in sorted(target.values(), key=lambda t: t.name):
            for stmt in room_ddl(table):
                print(f'            """{stmt}""",')
        return 0

    if args.print_shapes:
        for table in sorted(v2.values(), key=lambda t: t.name):
            ddl = room_ddl(table)
            columns = ", ".join(f'"{c.name}"' for c in table.columns)
            print("        LegacyTableShape(")
            print(f'            tableName = "{table.name}",')
            print(f"            columns = listOf({columns}),")
            print(f'            createTableSql = """{ddl[0]}""",')
            print("            indexSql = listOf(")
            for stmt in ddl[1:]:
                print(f'                """{stmt}""",')
            print("            )")
            print("        ),")
        return 0

    failures: list[str] = []

    migration_source = (ROOT / MIGRATION_DIR / "DatabaseMigrations.kt").read_text(encoding="utf-8")
    v2_catalog = ddl_literals(migration_source, "LegacyV2Schema")
    v2_create = list(v2_catalog.values())
    v1_to_v2 = migration_statements(migration_source, "1To2", strict=False)
    v2_to_v3 = migration_statements(migration_source, "2To3")

    # 1. the historical catalog must reproduce the schema v2 entities exactly
    conn = sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys = ON")
    for stmt in v2_create:
        conn.executescript(stmt)
    for table in v2.values():
        failures += [f"v1->v2 catalog: {p}" for p in compare_table(table, conn)]
    catalog_tables = {n for n, sql in v2_catalog.items() if sql.upper().startswith("CREATE TABLE")}
    missing_from_catalog = {t.name for t in v2.values()} - catalog_tables
    if missing_from_catalog:
        failures.append(f"v1->v2 catalog misses tables: {sorted(missing_from_catalog)}")

    # 2. v2 -> v3 on a database seeded with legacy data
    seed_v2_data(conn)
    before = {
        t: conn.execute(f"SELECT COUNT(*) FROM `{t}`").fetchone()[0]
        for t in v2_catalog
        if t in actual_tables(conn)
    }
    for stmt in v2_to_v3:
        try:
            conn.executescript(stmt)
        except sqlite3.Error as exc:
            failures.append(f"v2->v3 statement failed: {exc}\n    SQL: {stmt}")
            break
    conn.commit()

    if not failures:
        for table in sorted(v3.values(), key=lambda t: t.name):
            failures += [f"v2->v3 result: {p}" for p in compare_table(table, conn)]
        unexpected = actual_tables(conn) - {t.name for t in v3.values()} - {"room_master_table"}
        if unexpected:
            failures.append(f"v2->v3 result: unexpected tables left behind: {sorted(unexpected)}")

        violations = conn.execute("PRAGMA foreign_key_check").fetchall()
        if violations:
            failures.append(f"v2->v3 result: foreign key violations {violations}")

        preserved = {
            "properties": 3, "property_images": 3, "market_data": 3, "rent_estimates": 3,
            "tax_records": 3, "sales_history": 3, "financial_analyses": 3, "financing_scenarios": 3,
            "property_comps": 3,  # 4 legacy comps, 2 of them exact duplicates
            "saved_properties": 1, "saved_deals": 1, "ai_conversations": 2, "ai_messages": 1,
            "offers": 1, "offer_documents": 1, "automation_runs": 1, "automation_logs": 1,
            "automation_state": 1, "automation_jobs": 1, "automation_rules": 1,
            "api_configurations": 1, "gmail_configuration": 1, "offer_templates": 1,
        }
        for table, expected in preserved.items():
            if table not in actual_tables(conn):
                failures.append(f"data preservation: table `{table}` was dropped")
                continue
            got = conn.execute(f"SELECT COUNT(*) FROM `{table}`").fetchone()[0]
            if got != expected:
                failures.append(f"data preservation: `{table}` has {got} rows, expected {expected}")
        for table, was in before.items():
            if table == "comparable_properties" or table not in actual_tables(conn):
                continue
            got = conn.execute(f"SELECT COUNT(*) FROM `{table}`").fetchone()[0]
            if got < was:
                failures.append(f"data preservation: `{table}` lost rows ({was} -> {got})")

        keyed = conn.execute("SELECT COUNT(*) FROM properties WHERE canonicalKey IS NOT NULL").fetchone()[0]
        if keyed != 0:
            failures.append("backfill: legacy rows must start with canonicalKey = NULL")
        provenance = conn.execute("SELECT COUNT(*) FROM property_provenance").fetchone()[0]
        if provenance != 3:
            failures.append(f"backfill: expected 3 provenance rows for legacy properties, got {provenance}")
        sources = conn.execute("SELECT COUNT(*) FROM property_sources").fetchone()[0]
        if sources < 2:
            failures.append(f"backfill: default property sources were not seeded (got {sources})")
        financials = conn.execute("SELECT COUNT(*) FROM property_financials").fetchone()[0]
        if financials != 3:
            failures.append(f"backfill: property_financials has {financials} rows, expected 3")
        primary_linked = conn.execute(
            "SELECT COUNT(*) FROM properties WHERE primarySourceId IS NOT NULL"
        ).fetchone()[0]
        if primary_linked != 3:
            failures.append(f"backfill: primarySourceId not linked for legacy rows ({primary_linked}/3)")
        comp_row = conn.execute(
            "SELECT sourceId, compStatus, similarityScore FROM property_comps LIMIT 1"
        ).fetchone()
        if comp_row is None:
            failures.append("backfill: property_comps is empty")

        # dedup / integrity behaviours enforced by the new schema: first insert must succeed,
        # the second one (same dedup key, different primary key) must hit a constraint error.
        integrity_checks = [
            (
                "unique canonicalKey",
                "INSERT INTO properties (id, sourceType, title, address, city, state, zipCode, "
                "latitude, longitude, price, propertyType, bedrooms, bathrooms, squareFeet, yearBuilt, "
                "lotSizeSqFt, description, status, primaryImageUrl, scannedAt, isSaved, isSavedDeal, "
                "dealScore, unitNumber, normalizedAddress, canonicalKey, county, countyFips, apn, "
                "mlsNumber, propertySubType, halfBathrooms, stories, garageSpaces, hasPool, hoaMonthly, "
                "listingStatusUpdatedAt, lastVerifiedAt) VALUES (?, 'ON_MARKET', 't', 'a', 'c', 'TX', "
                "'78704', 0, 0, 1, 'Single Family', 1, 1.0, 1, 2000, 1, '', 'Active', '', 0, 0, 0, 0, "
                "'', 'a', 'same-key', '', '', '', '', '', 0, 0, 0, 0, 0.0, 0, 0)",
                ("dup-1", "dup-2"),
            ),
            (
                "unique provenance (sourceId, externalId)",
                "INSERT INTO property_provenance (propertyId, sourceId, externalId, externalUrl, "
                "ingestionMethod, confidence, isPrimaryForProperty, fetchedAt, sourceUpdatedAt, "
                "firstSeenAt, lastSeenAt, rawPayloadHash, rawPayloadRef) VALUES ('prop-old-1', "
                "'src-mls-default', ?, '', 'SEED', 1.0, 1, 1, 0, 1, 1, '', '')",
                ("ext-x", "ext-x"),
            ),
            (
                "unique comp dedup key",
                "INSERT INTO property_comps (targetPropertyId, compAddress, compPrice, compBeds, "
                "compBaths, compSqFt, distanceMiles, saleDate, adjustmentAmount, compUnit, compCity, "
                "compState, compZipCode, compPropertyType, compYearBuilt, compLotSizeSqFt, compStatus, "
                "compLatitude, compLongitude, pricePerSqFt, adjustedPrice, similarityScore, "
                "adjustmentsJson, isActiveListing) VALUES ('prop-old-1', ?, 515000, 4, 3.0, 2300, 0.1, "
                "'2025-01-01', 0, '', 'Austin', 'TX', '78704', 'Single Family', 2000, 5000, 'SOLD', 0, 0, "
                "0, 0, 0, '', 0)",
                ("9999 Test St", "9999 Test St"),
            ),
            (
                "unique enrichment (propertyId, type, provider)",
                "INSERT INTO property_enrichments (propertyId, enrichmentType, provider, valueNumeric, "
                "valueText, unit, confidence, effectiveAt, expiresAt, isCurrent, ingestedAt, payloadHash, "
                "rawPayloadRef, provenanceId, notes) VALUES ('prop-old-1', 'WALK_SCORE', ?, 1, '', '', 1, "
                "0, 0, 1, 0, '', '', NULL, '')",
                ("walkapi", "walkapi"),
            ),
        ]
        for label, sql, (first_value, second_value) in integrity_checks:
            try:
                conn.execute(sql, (first_value,))
            except sqlite3.Error as exc:
                failures.append(f"integrity check `{label}`: first insert failed: {exc}")
                conn.rollback()
                continue
            try:
                conn.execute(sql, (second_value,))
                failures.append(f"integrity check `{label}`: duplicate insert was accepted")
            except sqlite3.IntegrityError:
                pass
            conn.rollback()

        # cascade delete behaviour
        conn.execute("DELETE FROM properties WHERE id = 'prop-old-2'")
        conn.commit()
        orphans = {
            "property_images": "propertyId", "market_data": "propertyId", "rent_estimates": "propertyId",
            "tax_records": "propertyId", "sales_history": "propertyId", "property_comps": "targetPropertyId",
            "property_provenance": "propertyId", "property_financials": "propertyId",
            "financial_analyses": "propertyId", "financing_scenarios": "propertyId",
        }
        for table, column in orphans.items():
            left = conn.execute(
                f"SELECT COUNT(*) FROM `{table}` WHERE `{column}` = 'prop-old-2'"
            ).fetchone()[0]
            if left:
                failures.append(f"cascade delete: `{table}` kept {left} orphan rows")
        kept_offers = conn.execute(
            "SELECT COUNT(*) FROM offers WHERE propertyId = 'prop-old-1'"
        ).fetchone()[0]
        if kept_offers != 1:
            failures.append("cascade delete: offers must survive property deletion (soft reference)")
        kept_conv = conn.execute(
            "SELECT COUNT(*) FROM ai_conversations WHERE propertyId IS NULL"
        ).fetchone()[0]
        if kept_conv != 1:
            failures.append("cascade delete: ai conversation must survive with propertyId = NULL")

    # 3. DAO query validation against the migrated database
    if not failures:
        failures += [f"dao query: {p}" for p in check_queries(conn, dao_queries())]

    if failures:
        print(f"FAILED ({len(failures)} problem(s)):")
        for problem in failures:
            print(" - " + problem)
        return 1

    print("room data layer guard: OK")
    print(
        f"  entities v3: {len(v3)} tables, {sum(len(t.indices) for t in v3.values())} indices, "
        f"{sum(len(t.foreign_keys) for t in v3.values())} foreign keys"
    )
    print(f"  entities v2 (git HEAD): {len(v2)} tables")
    print(f"  legacy v2 catalog objects: {len(v2_create)}")
    print(f"  v1->v2 dynamic statements: {len(v1_to_v2)}")
    print(f"  v2->v3 migration statements: {len(v2_to_v3)}")
    print(f"  dao queries prepared: {sum(len(v) for v in dao_queries().values())}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
