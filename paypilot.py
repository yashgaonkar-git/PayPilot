"""Local PayPilot HTTP application and maintenance commands."""
import argparse
import base64
import calendar
import hashlib
import hmac
import json
import os
import re
import secrets
import smtplib
import sqlite3
import ssl
import time
from contextlib import contextmanager
from datetime import date, timedelta
from decimal import Decimal
from email.message import EmailMessage
from getpass import getpass
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from cryptography.fernet import Fernet

STATIC = Path(__file__).resolve().parent / "static"
DATA = Path(os.environ.get("PAYPILOT_DATA", ".paypilot")).resolve()
METHODS = ("Online banking", "Bank transfer", "Direct debit", "Debit card", "Credit card", "Digital wallet")
CATEGORIES = ("Utilities", "House Rent", "Groceries", "Internet Charges", "Retirement Charges",
              "Cell Phone Charges", "Debt Payments", "Subscriptions", "Insurance")
FREQUENCIES = ("Off", "Daily", "Weekly", "Bi-weekly", "Monthly")
PAYEES = {
    "Home Rental Services": ("14 Maple Avenue, Pune", "Demo Bank •••• 2134"),
    "City Power": ("52 East Street, Mumbai", "Demo Bank •••• 0975"),
    "Metro Fiber": ("16 Park Road, Bengaluru", "Demo Bank •••• 4410"),
}
FUNDING = ("Demo current •••• 4582", "Demo savings •••• 0921")
TEST_INSTITUTIONS = ("Demo Bank", "Demo Wallet")
TEST_OUTCOMES = ("Approve", "Decline")
SMS_PREVIEW = "SMS preview · Demo handset"
MAX_PDF = 3 * 1024 * 1024
SESSION_SECONDS = 8 * 3600
CIPHER = None
OTP_KEY = None
DEMO = False

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
 id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL, email TEXT UNIQUE NOT NULL,
 password TEXT NOT NULL, status TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'user',
 attempts INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0,
 details BLOB, pin TEXT);
CREATE TABLE IF NOT EXISTS registered_pan (
 fingerprint TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id));
CREATE TABLE IF NOT EXISTS sessions (
 token TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS challenges (
 token TEXT PRIMARY KEY, answer TEXT NOT NULL, expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS codes (
 token TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), purpose TEXT NOT NULL,
 digest TEXT NOT NULL, expires INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS throttle (
 bucket TEXT PRIMARY KEY, hits INTEGER NOT NULL, expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS bills (
 id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id),
 series TEXT, due TEXT NOT NULL, data BLOB NOT NULL, archived INTEGER NOT NULL DEFAULT 0,
 UNIQUE(series, due));
CREATE TABLE IF NOT EXISTS attachments (
 bill_id INTEGER PRIMARY KEY REFERENCES bills(id), name TEXT NOT NULL, content BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS schedules (
 id INTEGER PRIMARY KEY, bill_id INTEGER NOT NULL REFERENCES bills(id),
 day TEXT NOT NULL, status TEXT NOT NULL, data BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS payments (
 id INTEGER PRIMARY KEY, bill_id INTEGER NOT NULL REFERENCES bills(id),
 schedule_id INTEGER UNIQUE REFERENCES schedules(id), day TEXT NOT NULL, data BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS payment_attempts (
 id INTEGER PRIMARY KEY, reference TEXT UNIQUE NOT NULL, bill_id INTEGER NOT NULL REFERENCES bills(id),
 payment_id INTEGER UNIQUE REFERENCES payments(id), day TEXT NOT NULL, data BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS notifications (
 id INTEGER PRIMARY KEY, bill_id INTEGER NOT NULL REFERENCES bills(id),
 day TEXT NOT NULL, content BLOB NOT NULL, delivery TEXT NOT NULL,
 read INTEGER NOT NULL DEFAULT 0, UNIQUE(bill_id, day));
CREATE TABLE IF NOT EXISTS requests (
 user_id INTEGER NOT NULL REFERENCES users(id), token TEXT NOT NULL, action TEXT NOT NULL,
 response TEXT NOT NULL, expires INTEGER NOT NULL, PRIMARY KEY(user_id,token));
CREATE TABLE IF NOT EXISTS audit (
 id INTEGER PRIMARY KEY, actor INTEGER REFERENCES users(id), action TEXT NOT NULL,
 target TEXT NOT NULL, occurred TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX IF NOT EXISTS bills_owner ON bills(user_id);
CREATE INDEX IF NOT EXISTS schedules_bill ON schedules(bill_id);
CREATE INDEX IF NOT EXISTS payments_bill ON payments(bill_id);
CREATE INDEX IF NOT EXISTS payment_attempts_bill ON payment_attempts(bill_id);
"""


class Problem(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


@contextmanager
def connect(write=False):
    db = sqlite3.connect(DATA / "app.sqlite3", timeout=10, isolation_level=None)
    try:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys = ON")
        db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
    finally:
        db.close()


def initialize():
    global CIPHER, OTP_KEY
    os.umask(0o077)
    DATA.mkdir(mode=0o700, parents=True, exist_ok=True)
    key_path = DATA / "secret.key"
    try:
        with key_path.open("xb") as handle:
            handle.write(Fernet.generate_key())
    except FileExistsError:
        pass
    key = key_path.read_bytes()
    CIPHER = Fernet(key)
    OTP_KEY = base64.urlsafe_b64decode(key)
    db = sqlite3.connect(DATA / "app.sqlite3", timeout=10)
    try:
        db.execute("PRAGMA journal_mode=WAL")
        db.executescript(SCHEMA)
    finally:
        db.close()


def pack(value):
    return CIPHER.encrypt(json.dumps(value, ensure_ascii=False).encode())


def unpack(value):
    return json.loads(CIPHER.decrypt(value))


def digest(value):
    return hmac.new(OTP_KEY, value.encode(), hashlib.sha256).hexdigest()


def password_hash(password, salt=None):
    salt = salt or secrets.token_hex(16)
    key = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1)
    return salt + ":" + key.hex()


def verify_password(password, stored):
    return hmac.compare_digest(password_hash(password, stored.split(":")[0]), stored)


def audit(db, actor, action, target):
    db.execute("INSERT INTO audit(actor,action,target) VALUES(?,?,?)", (actor, action, str(target)))


def now():
    return int(time.time())


def field(data, key, maximum=120, required=True):
    value = data.get(key, "")
    if not isinstance(value, str) or len(value) > maximum:
        raise Problem(f"Invalid {key.replace('_', ' ')}.")
    value = value.strip()
    if required and not value:
        raise Problem(f"Enter {key.replace('_', ' ')}.")
    return value


def choice(data, key, values):
    value = field(data, key)
    if value not in values:
        raise Problem(f"Choose a valid {key.replace('_', ' ')}.")
    return value


def day_field(data, key, required=True):
    value = field(data, key, 10, required)
    if not value:
        return ""
    try:
        if date.fromisoformat(value).isoformat() == value:
            return value
    except ValueError:
        pass
    raise Problem(f"Enter a valid {key.replace('_', ' ')}.")


def money(data, key="amount"):
    value = field(data, key, 12)
    if not re.fullmatch(r"[0-9]{1,8}(\.[0-9]{1,2})?", value) or Decimal(value) <= 0:
        raise Problem("Enter a positive amount with up to two decimal places.")
    return int(Decimal(value) * 100)


def credentials(data):
    username = field(data, "username", 40).lower()
    email = field(data, "email", 200).lower()
    if not re.fullmatch(r"[a-z0-9_.-]{3,40}", username):
        raise Problem("Use 3–40 letters, numbers, dots, underscores or hyphens for user ID.")
    if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email):
        raise Problem("Enter a valid email address.")
    return username, email


def new_password(data):
    value = data.get("password")
    if not isinstance(value, str) or not 10 <= len(value) <= 128:
        raise Problem("Use 10–128 characters for your password.")
    if value != data.get("confirm"):
        raise Problem("The passwords must match.")
    return password_hash(value)


def login_password(data):
    value = data.get("password")
    if not isinstance(value, str) or not value or len(value) > 128:
        raise Problem("Enter your password.")
    return value


def limit(db, bucket, cap, seconds):
    row = db.execute("SELECT * FROM throttle WHERE bucket=?", (bucket,)).fetchone()
    if row and row["expires"] > now():
        if row["hits"] >= cap:
            raise Problem("Too many attempts. Try again later.", 429)
        db.execute("UPDATE throttle SET hits=hits+1 WHERE bucket=?", (bucket,))
    else:
        db.execute("INSERT OR REPLACE INTO throttle VALUES(?,1,?)", (bucket, now() + seconds))


def send_mail(recipient, subject, body):
    if not os.environ.get("PAYPILOT_SMTP_HOST"):
        return False
    sender = os.environ["PAYPILOT_SMTP_FROM"]
    message = EmailMessage()
    message["From"] = sender
    message["To"] = recipient
    message["Subject"] = subject
    message.set_content(body)
    with smtplib.SMTP_SSL(os.environ["PAYPILOT_SMTP_HOST"],
                          int(os.environ.get("PAYPILOT_SMTP_PORT", "465")),
                          timeout=10, context=ssl.create_default_context()) as smtp:
        if os.environ.get("PAYPILOT_SMTP_USER"):
            smtp.login(os.environ["PAYPILOT_SMTP_USER"], os.environ["PAYPILOT_SMTP_PASSWORD"])
        smtp.send_message(message)
    return True


def issue_code(db, user, purpose):
    if not os.environ.get("PAYPILOT_SMTP_HOST") and not DEMO:
        raise Problem("Email delivery is unavailable. Contact your PayPilot administrator.", 503)
    if db.execute("SELECT 1 FROM codes WHERE user_id=? AND purpose=? AND expires>?",
                  (user["id"], purpose, now() + 540)).fetchone():
        raise Problem("Wait one minute before requesting another code.", 429)
    code = f"{secrets.randbelow(1000000):06d}"
    token = secrets.token_urlsafe(32)
    db.execute("DELETE FROM codes WHERE user_id=? AND purpose=?", (user["id"], purpose))
    db.execute("INSERT INTO codes VALUES(?,?,?,?,?,0)",
               (digest(token), user["id"], purpose, digest(token + code), now() + 600))
    return token, code


def read_code(db, data, purpose):
    token = field(data, "token", 100)
    code = field(data, "code", 10)
    row = db.execute("SELECT * FROM codes WHERE token=? AND purpose=?", (digest(token), purpose)).fetchone()
    if not row or row["expires"] <= now() or row["attempts"] >= 5:
        return None
    db.execute("UPDATE codes SET attempts=attempts+1 WHERE token=?", (row["token"],))
    if not hmac.compare_digest(digest(token + code), row["digest"]):
        return None
    return db.execute("SELECT * FROM users WHERE id=? AND status='approved'", (row["user_id"],)).fetchone()


def start_session(db, user_id):
    token = secrets.token_urlsafe(32)
    db.execute("INSERT INTO sessions VALUES(?,?,?)", (digest(token), user_id, now() + SESSION_SECONDS))
    audit(db, user_id, "sign-in", user_id)
    return token


def add_month(anchor, offset):
    month = anchor.month - 1 + offset
    year, month = anchor.year + month // 12, month % 12 + 1
    return date(year, month, min(anchor.day, calendar.monthrange(year, month)[1]))


def bills_for(db, user_id, archived=False):
    clause = "" if archived else "AND archived=0"
    return [dict(unpack(row["data"]), id=row["id"], due=row["due"], series=row["series"],
                 archived=bool(row["archived"]))
            for row in db.execute(f"SELECT * FROM bills WHERE user_id=? {clause} ORDER BY due,id", (user_id,))]


def schedule_rows(db, user_id):
    return [dict(unpack(row["data"]), id=row["id"], bill_id=row["bill_id"],
                 day=row["day"], status=row["status"], bill_name=unpack(row["bill"])["name"],
                 category=unpack(row["bill"])["category"])
            for row in db.execute("""SELECT s.*,b.data AS bill FROM schedules s JOIN bills b
                                     ON s.bill_id=b.id WHERE b.user_id=? ORDER BY s.day,s.id""", (user_id,))]


def payment_rows(db, user_id):
    return [dict(unpack(row["data"]), id=row["id"], bill_id=row["bill_id"], day=row["day"],
                 bill_name=unpack(row["bill"])["name"], category=unpack(row["bill"])["category"],
                 due=row["due"])
            for row in db.execute("""SELECT p.*,b.data AS bill,b.due FROM payments p JOIN bills b
                                     ON p.bill_id=b.id WHERE b.user_id=? ORDER BY p.day DESC,p.id DESC""", (user_id,))]


def get_bill(db, user_id, bill_id):
    row = db.execute("SELECT * FROM bills WHERE id=? AND user_id=? AND archived=0",
                     (bill_id, user_id)).fetchone()
    if not row:
        raise Problem("Bill not found.", 404)
    return row, unpack(row["data"])


def insert_bill(db, user_id, due, payload, series=None):
    return db.execute("INSERT INTO bills(user_id,series,due,data) VALUES(?,?,?,?)",
                      (user_id, series, due, pack(payload))).lastrowid


def replicate(db, owner, due, payload, series):
    anchor = date.fromisoformat(due)
    if anchor.year >= 9998:
        raise Problem("Choose a nearer date for recurring bills.")
    # Keep the original day of the month, including after shorter months.
    if payload["payment_frequency"] in ("Weekly", "Bi-weekly"):
        step = 7 if payload["payment_frequency"] == "Weekly" else 14
        dates = [anchor + timedelta(days=step * i) for i in range(1, 91 // step + 1)]
    else:
        months = 3 if payload["payment_frequency"] == "Monthly" else 12
        step = 1 if months == 3 else 12
        dates = [add_month(anchor, offset) for offset in range(step, months + 1, step)]
    for day in dates:
        item = dict(payload, attachment=False, snoozed="")
        if item["reminder_start"]:
            item["reminder_start"] = (day - (anchor - date.fromisoformat(item["reminder_start"]))).isoformat()
        db.execute("INSERT OR IGNORE INTO bills(user_id,series,due,data) VALUES(?,?,?,?)",
                   (owner, series, day.isoformat(), pack(item)))


def account_amounts(db, bill_id, exclude=None):
    paid = sum(unpack(r[0])["amount"] for r in db.execute("SELECT data FROM payments WHERE bill_id=?", (bill_id,)))
    scheduled = sum(unpack(r["data"])["amount"] for r in db.execute(
        "SELECT id,data FROM schedules WHERE bill_id=? AND status='Scheduled'", (bill_id,)) if r["id"] != exclude)
    return paid, scheduled


def cancel_schedules(db, bill_id, actor, spare=0):
    rows = db.execute("SELECT id,data FROM schedules WHERE bill_id=? AND status='Scheduled' ORDER BY day,id",
                      (bill_id,)).fetchall()
    for row in rows:
        amount = unpack(row["data"])["amount"]
        if amount <= spare:
            spare -= amount
        else:
            db.execute("UPDATE schedules SET status='Cancelled' WHERE id=?", (row["id"],))
            audit(db, actor, "schedule.cancelled", row["id"])


def record_payment(db, user_id, bill_id, amount, method, source, day, schedule_id=None):
    _, bill = get_bill(db, user_id, bill_id)
    paid, _ = account_amounts(db, bill_id)
    if amount <= 0 or amount > bill["amount"] - paid:
        raise Problem("Payment must be within the outstanding balance.")
    payment_id = db.execute("INSERT INTO payments(bill_id,schedule_id,day,data) VALUES(?,?,?,?)",
                            (bill_id, schedule_id, day, pack(dict(amount=amount, method=method, source=source)))).lastrowid
    if schedule_id:
        db.execute("UPDATE schedules SET status='Completed' WHERE id=?", (schedule_id,))
    cancel_schedules(db, bill_id, user_id, bill["amount"] - paid - amount)
    audit(db, user_id, "payment.recorded", payment_id)
    return payment_id


def run_test_payment(db, owner, bill_id, amount, method, institution, outcome, day, schedule_id=None):
    _, bill = get_bill(db, owner, bill_id)
    reference = "PP-TEST-" + secrets.token_hex(8).upper()
    source = "Simulated schedule" if schedule_id else "Simulated Pay Now"
    payment_id = None
    if outcome == "Approve":
        payment_id = record_payment(db, owner, bill_id, amount, method, source, day, schedule_id)
    attempt = dict(bill_name=bill["name"], category=bill["category"],
                   amount=amount, method=method, institution=institution, source=source,
                   status="Settled" if payment_id else "Declined")
    db.execute("INSERT INTO payment_attempts(reference,bill_id,payment_id,day,data) VALUES(?,?,?,?,?)",
               (reference, bill_id, payment_id, day, pack(attempt)))
    audit(db, owner, "test-payment." + attempt["status"].lower(), reference)
    return dict(attempt, reference=reference, day=day)


def due_schedules(db, owner=None):
    query = """SELECT s.*,b.user_id FROM schedules s JOIN bills b ON s.bill_id=b.id
               WHERE s.status='Scheduled' AND s.day<=? AND b.archived=0"""
    today = date.today().isoformat()
    args = [today]
    if owner is not None:
        query += " AND b.user_id=?"
        args.append(owner)
    for row in db.execute(query + " ORDER BY s.day,s.id", args).fetchall():
        status = db.execute("SELECT status FROM schedules WHERE id=?", (row["id"],)).fetchone()["status"]
        if status != "Scheduled":
            continue
        item = unpack(row["data"])
        run_test_payment(db, row["user_id"], row["bill_id"], item["amount"], item["method"],
                         TEST_INSTITUTIONS[0], "Approve", today, row["id"])


def reminder_due(bill, today):
    start = bill["reminder_start"]
    current_day = today.isoformat()
    if bill["reminder_frequency"] == "Off" or not start or start > current_day or bill["snoozed"] > current_day:
        return False
    if bill["snoozed"] == current_day:
        return True
    if bill["due"] < current_day:
        return False
    begin = date.fromisoformat(start)
    interval = {"Daily": 1, "Weekly": 7, "Bi-weekly": 14}.get(bill["reminder_frequency"])
    if interval:
        return (today - begin).days % interval == 0
    months = (today.year - begin.year) * 12 + today.month - begin.month
    return add_month(begin, months) == today


def generate_reminders(db, owner):
    today = date.today()
    for bill in bills_for(db, owner):
        if bill["series"] and not bill["remind_recurring"]:
            first = db.execute("SELECT MIN(due) FROM bills WHERE series=?", (bill["series"],)).fetchone()[0]
            if first != bill["due"]:
                continue
        paid, _ = account_amounts(db, bill["id"])
        if paid == bill["amount"] or not reminder_due(bill, today):
            continue
        content = bill["reminder_message"] or f"{bill['name']} is due on {bill['due']}."
        delivery = {"Email": "Pending email", "Contact": SMS_PREVIEW}.get(bill["channel"], "In app")
        db.execute("INSERT OR IGNORE INTO notifications(bill_id,day,content,delivery) VALUES(?,?,?,?)",
                   (bill["id"], today.isoformat(), pack(content), delivery))


def state(db, user):
    owner = user["id"]
    payments = payment_rows(db, owner)
    schedules = schedule_rows(db, owner)
    bills = bills_for(db, owner)
    today = date.today().isoformat()
    paid, reserved = {}, {}
    for p in payments:
        paid[p["bill_id"]] = paid.get(p["bill_id"], 0) + p["amount"]
    for s in schedules:
        if s["status"] == "Scheduled":
            reserved[s["bill_id"]] = reserved.get(s["bill_id"], 0) + s["amount"]
    for b in bills:
        b["paid"] = paid.get(b["id"], 0)
        b["scheduled"] = reserved.get(b["id"], 0)
        b["balance"] = b["amount"] - b["paid"]
        b["overdue_days"] = (date.today() - date.fromisoformat(b["due"])).days if b["balance"] and b["due"] < today else 0
    notes = [dict(id=r["id"], day=r["day"], message=unpack(r["content"]),
                  delivery=SMS_PREVIEW if r["delivery"] == "Contact delivery unavailable" else r["delivery"],
                  read=bool(r["read"]))
             for r in db.execute("""SELECT n.* FROM notifications n JOIN bills b ON b.id=n.bill_id
                                   WHERE b.user_id=? ORDER BY n.day DESC,n.id DESC""", (owner,))]
    attempts = [dict(unpack(row["data"]), reference=row["reference"], day=row["day"])
                for row in db.execute("""SELECT a.* FROM payment_attempts a JOIN bills b ON b.id=a.bill_id
                                         WHERE b.user_id=? ORDER BY a.id DESC""", (owner,))]
    return dict(user=dict(username=user["username"], email=user["email"], role=user["role"], pin=bool(user["pin"])),
                today=today, bills=bills, payments=payments, schedules=schedules,
                notifications=notes, payment_attempts=attempts, methods=METHODS, categories=CATEGORIES,
                frequencies=FREQUENCIES, payees=PAYEES, funding=FUNDING,
                test_institutions=TEST_INSTITUTIONS, test_outcomes=TEST_OUTCOMES)


def save_bill(db, owner, data, bill_id=None):
    due = day_field(data, "due")
    amount = money(data)
    repeat = choice(data, "payment_frequency", ("Once", "Weekly", "Bi-weekly", "Monthly", "Yearly"))
    frequency = choice(data, "reminder_frequency", FREQUENCIES)
    start = day_field(data, "reminder_start", False)
    if frequency != "Off" and not start:
        due_date = date.fromisoformat(due)
        start = (due_date - timedelta(days=min(3, due_date.toordinal() - 1))).isoformat()
    if frequency != "Off" and start >= due:
        raise Problem("The reminder must start before the bill is due.")
    item = dict(name=field(data, "name"), company=field(data, "company"), category=field(data, "category", 60),
                amount=amount, payment_frequency=repeat, reminder_frequency=frequency, reminder_start=start,
                reminder_message=field(data, "reminder_message", 250, False),
                channel=choice(data, "channel", ("In app", "Email", "Contact")),
                remind_recurring=data.get("remind_recurring") is True, notes=field(data, "notes", 1000, False),
                priority=choice(data, "priority", ("Normal", "High")), attachment=False, snoozed="")
    if bill_id:
        row, previous = get_bill(db, owner, bill_id)
        paid, reserved = account_amounts(db, bill_id)
        if paid + reserved > amount:
            raise Problem("The amount must cover recorded and scheduled payments.")
        if row["series"] and (row["due"] != due or previous["payment_frequency"] != repeat):
            raise Problem("Change recurrence or the due date by creating a new series. You can edit other fields on this occurrence.")
        item.update(snoozed=previous["snoozed"], attachment=previous["attachment"])
        series = row["series"] or (secrets.token_hex(16) if repeat != "Once" else None)
        db.execute("UPDATE bills SET due=?,data=?,series=? WHERE id=?", (due, pack(item), series, bill_id))
        if series and not row["series"]:
            replicate(db, owner, due, item, series)
    else:
        series = secrets.token_hex(16) if repeat != "Once" else None
        bill_id = insert_bill(db, owner, due, item, series)
        if series:
            replicate(db, owner, due, item, series)
    raw = data.get("attachment")
    if raw:
        try:
            attachment = base64.b64decode(raw, validate=True)
        except (ValueError, TypeError):
            raise Problem("Invalid attachment.") from None
        name = field(data, "filename", 160)
        if (len(attachment) > MAX_PDF or not name.lower().endswith(".pdf")
                or not attachment.startswith(b"%PDF-") or b"%%EOF" not in attachment[-2048:]):
            raise Problem("Choose a valid PDF up to 3 MB.")
        db.execute("INSERT OR REPLACE INTO attachments VALUES(?,?,?)",
                   (bill_id, "bill.pdf", CIPHER.encrypt(attachment)))
        item["attachment"] = True
    elif data.get("remove_attachment") is True:
        db.execute("DELETE FROM attachments WHERE bill_id=?", (bill_id,))
        item["attachment"] = False
    db.execute("UPDATE bills SET data=? WHERE id=?", (pack(item), bill_id))
    audit(db, owner, "bill.saved", bill_id)
    return bill_id


def save_schedule(db, owner, data, schedule_id=None):
    today = date.today().isoformat()
    scheduled = day_field(data, "day")
    if scheduled < today:
        raise Problem("The payment date cannot be in the past.")
    if schedule_id is not None:
        row = db.execute("""SELECT s.* FROM schedules s JOIN bills b ON s.bill_id=b.id
                            WHERE s.id=? AND b.user_id=? AND s.status='Scheduled'""",
                         (schedule_id, owner)).fetchone()
        if not row:
            raise Problem("Active schedule not found.", 404)
        db.execute("UPDATE schedules SET day=? WHERE id=?", (scheduled, schedule_id))
        audit(db, owner, "schedule.rescheduled", schedule_id)
        return
    bill_id = integer_id(data.get("bill_id"))
    row, bill = get_bill(db, owner, bill_id)
    frequency = choice(data, "frequency", ("Once", bill["payment_frequency"]))
    payload = dict(amount=money(data), purpose=field(data, "purpose"), bill_date=day_field(data, "bill_date"),
                   payee=choice(data, "payee", PAYEES), method=choice(data, "method", METHODS),
                   funding=choice(data, "funding", FUNDING), frequency=frequency)
    payload["address"], payload["bank"] = PAYEES[payload["payee"]]
    targets = [(bill_id, scheduled, payload["bill_date"], bill)]
    if frequency != "Once" and row["series"]:
        day_offset = date.fromisoformat(scheduled) - date.fromisoformat(row["due"])
        bill_offset = date.fromisoformat(payload["bill_date"]) - date.fromisoformat(row["due"])
        for instance in db.execute("SELECT * FROM bills WHERE series=? AND due>? AND archived=0 ORDER BY due",
                                   (row["series"], row["due"])):
            due = date.fromisoformat(instance["due"])
            targets.append((instance["id"], (due + day_offset).isoformat(),
                            (due + bill_offset).isoformat(), unpack(instance["data"])))
    for target, scheduled_day, bill_day, details in targets:
        paid, reserved = account_amounts(db, target)
        if payload["amount"] > details["amount"] - paid - reserved:
            raise Problem("The amount exceeds the unreserved balance of a selected occurrence.")
        new_id = db.execute("INSERT INTO schedules(bill_id,day,status,data) VALUES(?,?,'Scheduled',?)",
                            (target, scheduled_day, pack(dict(payload, bill_date=bill_day)))).lastrowid
        audit(db, owner, "schedule.created", new_id)


def integer_id(value):
    if type(value) is not int or not 0 < value < 2**63:
        raise Problem("Invalid record ID.")
    return value


def bill_action(db, owner, action, data):
    ids = data.get("ids")
    if not isinstance(ids, list) or not 1 <= len(ids) <= 100:
        raise Problem("Select one or more bills.")
    method = "Marked paid" if action == "mark" else (
        choice(data, "method", METHODS) if action in ("pay", "record") else "")
    attempts = []
    if action == "pay":
        institution = choice({"institution": data.get("institution", TEST_INSTITUTIONS[0])},
                             "institution", TEST_INSTITUTIONS)
        outcome = choice({"outcome": data.get("outcome", "Approve")}, "outcome", TEST_OUTCOMES)
    today = date.today().isoformat()
    when = day_field(data, "day") if action in ("snooze", "record") else today
    if action == "snooze" and when <= today:
        raise Problem("Choose a snooze date after today.")
    if action == "record" and (len(ids) != 1 or when > today):
        raise Problem("Record one payment dated today or earlier.")
    for bill_id in dict.fromkeys(integer_id(value) for value in ids):
        _, item = get_bill(db, owner, bill_id)
        if action == "archive":
            db.execute("UPDATE bills SET archived=1 WHERE id=?", (bill_id,))
            cancel_schedules(db, bill_id, owner)
            audit(db, owner, "bill.archived", bill_id)
            continue
        paid, _ = account_amounts(db, bill_id)
        if paid >= item["amount"]:
            raise Problem("A selected bill is already paid.")
        if action == "snooze":
            item["snoozed"] = when
            db.execute("UPDATE bills SET data=? WHERE id=?", (pack(item), bill_id))
            audit(db, owner, "bill.snoozed", bill_id)
        else:
            amount = money(data) if action == "record" else item["amount"] - paid
            if action == "pay":
                attempts.append(run_test_payment(db, owner, bill_id, amount, method, institution, outcome, when))
            else:
                source = "Manual" if action == "mark" else "External payment"
                record_payment(db, owner, bill_id, amount, method, source, when)
    return attempts


def create_demo(db):
    name = "demo-" + secrets.token_hex(6)
    owner = db.execute("INSERT INTO users(username,email,password,status) VALUES(?,?,?,'approved')",
                       (name, name + "@example.invalid", password_hash(secrets.token_hex(24)))).lastrowid
    today = date.today()
    for name, company, category, days, amount, paid, priority in (
        ("Home rent", "Home Rental Services", "House Rent", 4, 1850000, 0, "High"),
        ("Electricity", "City Power", "Utilities", -3, 216000, 86000, "High"),
        ("Home internet", "Metro Fiber", "Internet Charges", 8, 119900, 0, "Normal"),
        ("Music subscription", "Studio Audio", "Subscriptions", 14, 14900, 0, "Normal"),
        ("Mobile plan", "City Mobile", "Cell Phone Charges", -8, 69900, 69900, "Normal"),
        ("Health cover", "Harbor Insurance", "Insurance", -14, 329900, 329900, "Normal"),
    ):
        due = (today + timedelta(days=days)).isoformat()
        item = dict(name=name, company=company, category=category, amount=amount, payment_frequency="Once",
                    priority=priority, reminder_frequency="Off", reminder_start="", reminder_message="",
                    channel="In app", remind_recurring=True, notes="", attachment=False, snoozed="")
        bill_id = insert_bill(db, owner, due, item)
        if paid:
            record_payment(db, owner, bill_id, paid, "Digital wallet", "Demo data", due)
        if name == "Home internet":
            save_schedule(db, owner, dict(day=(today + timedelta(days=5)).isoformat(), bill_id=bill_id,
                          purpose="Monthly internet", bill_date=due, payee="Metro Fiber", method="Bank transfer",
                          funding=FUNDING[0], frequency="Once", amount="1199"))
        if name == "Home rent":
            db.execute("INSERT INTO notifications(bill_id,day,content,delivery) VALUES(?,?,?,?)",
                       (bill_id, today.isoformat(), pack("Home rent is due in 4 days."), SMS_PREVIEW))
    return start_session(db, owner)


class Handler(BaseHTTPRequestHandler):
    def finish_response(self, status, body, content_type, cookie=None, attachment=False):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy",
                         "default-src 'self'; script-src 'self'; style-src 'self'; "
                         "img-src 'self' data:; form-action 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'")
        if cookie is not None:
            secure = "; Secure" if os.environ.get("PAYPILOT_SECURE_COOKIE") == "1" else ""
            self.send_header("Set-Cookie", f"paypilot={cookie}; Path=/; HttpOnly; SameSite=Strict{secure}; Max-Age={SESSION_SECONDS if cookie else 0}")
        if attachment:
            self.send_header("Content-Disposition", 'attachment; filename="bill.pdf"')
        self.end_headers()
        self.wfile.write(body)

    def json(self, body, status=200, cookie=None):
        self.finish_response(status, json.dumps(body, ensure_ascii=False).encode(), "application/json", cookie)

    def input(self):
        # JSON with a custom header requires a same-origin request or a preflight. This server grants no CORS access.
        if self.headers.get("X-PayPilot") != "1" or self.headers.get_content_type() != "application/json":
            raise Problem("Invalid API request.", 403)
        fetch_site = self.headers.get("Sec-Fetch-Site")
        if fetch_site and fetch_site != "same-origin":
            raise Problem("Invalid request origin.", 403)
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= 4 * 1024 * 1024 + 65536:
            raise Problem("Invalid request size.", 413)
        obj = json.loads(self.rfile.read(length))
        if not isinstance(obj, dict):
            raise Problem("Expected a JSON object.")
        return obj

    def token(self):
        cookies = SimpleCookie(self.headers.get("Cookie", ""))
        return digest(cookies["paypilot"].value) if "paypilot" in cookies else ""

    def user(self, db):
        row = db.execute("""SELECT u.* FROM users u JOIN sessions s ON u.id=s.user_id
                            WHERE s.token=? AND s.expires>? AND u.status='approved'""",
                         (self.token(), now())).fetchone()
        if not row:
            raise Problem("Sign in to continue.", 401)
        return row

    def handle_error(self, exc):
        if isinstance(exc, Problem):
            self.json({"error": str(exc)}, exc.status)
        elif isinstance(exc, (ValueError, OverflowError)):
            self.json({"error": "Invalid request."}, 400)
        elif isinstance(exc, (OSError, smtplib.SMTPException)):
            self.log_error("I/O error (%s)", type(exc).__name__)
            self.json({"error": "The service is temporarily unavailable."}, 503)
        else:
            self.log_error("Application error (%s)", type(exc).__name__)
            self.json({"error": "The operation could not be completed."}, 500)

    def do_GET(self):
        try:
            path = self.path.partition("?")[0]
            if path in ("/", "/app.js", "/style.css"):
                filename, content_type = {
                    "/": ("index.html", "text/html; charset=utf-8"),
                    "/app.js": ("app.js", "text/javascript; charset=utf-8"),
                    "/style.css": ("style.css", "text/css; charset=utf-8"),
                }[path]
                self.finish_response(200, (STATIC / filename).read_bytes(), content_type)
            elif path == "/api/config":
                self.json({"demo": DEMO})
            elif path == "/api/state":
                with connect() as db:
                    result = state(db, self.user(db))
                self.json(result)
            elif path == "/api/access":
                with connect() as db:
                    user = self.user(db)
                    if user["role"] != "admin":
                        raise Problem("Administrator access is required.", 403)
                    result = [dict(r) for r in db.execute(
                        "SELECT id,username,email,status FROM users WHERE role='user' AND status IN ('pending','denied') ORDER BY id DESC")]
                self.json(result)
            elif re.fullmatch(r"/api/attachment/[0-9]+", path):
                with connect() as db:
                    owner = self.user(db)["id"]
                    bill_id = integer_id(int(path.rsplit("/", 1)[1]))
                    get_bill(db, owner, bill_id)
                    content = db.execute("SELECT content FROM attachments WHERE bill_id=?", (bill_id,)).fetchone()
                    if not content:
                        raise Problem("Attachment not found.", 404)
                self.finish_response(200, CIPHER.decrypt(content[0]), "application/pdf", attachment=True)
            else:
                raise Problem("Page not found.", 404)
        except Exception as exc:
            self.handle_error(exc)

    def do_POST(self):
        try:
            data = self.input()
            action = self.path
            auth = {
                "/api/challenge", "/api/register", "/api/login", "/api/reset",
                "/api/verify-login", "/api/verify-reset", "/api/demo",
            }
            if action in auth:
                body, status, cookie = self.authenticate(action, data)
            else:
                with connect(write=True) as db:
                    user = self.user(db)
                    body, cookie = self.apply_change(db, user, action, data)
                status = 200
            self.json(body, status, cookie)
        except Exception as exc:
            self.handle_error(exc)

    def authenticate(self, action, data):
        email = None
        token = code = None
        cookie = None
        failure = None
        result = {}
        with connect(write=True) as db:
            limit(db, "ip:" + self.client_address[0] + action, 40, 300)
            if action == "/api/challenge":
                db.execute("DELETE FROM challenges WHERE expires<=?", (now(),))
                symbols = ("◆", "●", "■", "▲", "★", "♥")
                first, second, third = secrets.SystemRandom().sample(symbols, 3)
                names = dict(zip(symbols, ("diamond", "circle", "square", "triangle", "star", "heart")))
                token = secrets.token_urlsafe(32)
                answer = secrets.choice((first, second, third))
                db.execute("INSERT INTO challenges VALUES(?,?,?)", (digest(token), answer, now() + 300))
                result = dict(token=token, shapes=(first, second, third), names=names, prompt=names[answer])
            elif action == "/api/demo":
                if not DEMO:
                    raise Problem("Demo accounts are disabled.", 404)
                limit(db, "demo:" + self.client_address[0], 12, 3600)
                cookie = create_demo(db)
            elif action == "/api/register":
                username, email_id = credentials(data)
                pan = field(data, "pan", 10).upper()
                account = field(data, "account", 18)
                ifsc = field(data, "ifsc", 11).upper()
                bank = field(data, "bank")
                if not re.fullmatch(r"[A-Z]{5}[0-9]{4}[A-Z]", pan):
                    raise Problem("Enter a PAN in the format ABCDE1234F.")
                if not re.fullmatch(r"[0-9]{6,18}", account) or not re.fullmatch(r"[A-Z]{4}0[A-Z0-9]{6}", ifsc):
                    raise Problem("Enter a valid account number format and IFSC.")
                password = new_password(data)
                try:
                    owner = db.execute("""INSERT INTO users(username,email,password,status,details)
                                          VALUES(?,?,?,'pending',?)""",
                                       (username, email_id, password, pack(dict(pan=pan, account=account,
                                                                              ifsc=ifsc, bank=bank)))).lastrowid
                    db.execute("INSERT INTO registered_pan VALUES(?,?)", (digest("pan:" + pan), owner))
                except sqlite3.IntegrityError:
                    raise Problem("A request or account already exists for this user ID, email or sample PAN.", 409) from None
                audit(db, owner, "access.requested", owner)
                result["message"] = "Access requested. Sign in once an administrator approves it."
            elif action == "/api/login":
                username = field(data, "username", 40).lower()
                password = login_password(data)
                challenge = digest(field(data, "challenge", 100))
                check = db.execute("SELECT * FROM challenges WHERE token=?", (challenge,)).fetchone()
                db.execute("DELETE FROM challenges WHERE token=?", (challenge,))
                if not check or check["expires"] <= now() or check["answer"] != field(data, "answer", 4):
                    failure = ("Screen verification failed. Try a fresh challenge.", 400)
                else:
                    user = db.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
                    if not user:
                        failure = ("Account does not exist. Request access to get started.", 401)
                    elif user["locked_until"] > now():
                        failure = ("Account is locked. Reset the password or try again in 15 minutes.", 423)
                    elif not verify_password(password, user["password"]):
                        attempts = (0 if user["locked_until"] else user["attempts"]) + 1
                        db.execute("UPDATE users SET attempts=?,locked_until=? WHERE id=?",
                                   (attempts, now() + 900 if attempts >= 3 else 0, user["id"]))
                        failure = ("Account locked for 15 minutes." if attempts >= 3 else "Invalid credentials.",
                                   423 if attempts >= 3 else 401)
                    elif user["status"] != "approved":
                        failure = ("Your account is awaiting approval." if user["status"] == "pending"
                                   else "Your access request was declined.", 403)
                    else:
                        db.execute("UPDATE users SET attempts=0,locked_until=0 WHERE id=?", (user["id"],))
                        token, code = issue_code(db, user, "login")
                        email = user["email"]
                        result = dict(token=token, pin=bool(user["pin"]))
            elif action == "/api/reset":
                username, email_id = credentials(data)
                user = db.execute("SELECT * FROM users WHERE username=? AND email=? AND status='approved'",
                                  (username, email_id)).fetchone()
                if user:
                    token, code = issue_code(db, user, "reset")
                    email = user["email"]
                result = dict(token=token or secrets.token_urlsafe(32),
                              message="If the account matches, a verification code will be sent.")
            else:
                purpose = "login" if action == "/api/verify-login" else "reset"
                user = read_code(db, data, purpose)
                if not user:
                    failure = ("Invalid or expired verification code.", 401)
                elif purpose == "login" and user["locked_until"] > now():
                    failure = ("Account is locked. Reset your password to continue.", 423)
                elif purpose == "login" and user["pin"] and not verify_password(field(data, "pin", 8), user["pin"]):
                    failure = ("Invalid access PIN.", 401)
                else:
                    if purpose == "login":
                        cookie = start_session(db, user["id"])
                    else:
                        db.execute("UPDATE users SET password=?,attempts=0,locked_until=0 WHERE id=?",
                                   (new_password(data), user["id"]))
                        db.execute("DELETE FROM sessions WHERE user_id=?", (user["id"],))
                        db.execute("DELETE FROM codes WHERE user_id=?", (user["id"],))
                        audit(db, user["id"], "password.reset", user["id"])
                        result["message"] = "Password updated. Sign in with your new password."
                    db.execute("DELETE FROM codes WHERE token=?", (digest(field(data, "token", 100)),))
        if failure:
            return dict(error=failure[0]), failure[1], None
        if email:
            try:
                delivered = send_mail(email, "PayPilot verification code", f"Your PayPilot code is {code}. It expires in 10 minutes.")
                if not delivered and DEMO:
                    print(f"Local verification code for {email}: {code}", flush=True)
            except (OSError, smtplib.SMTPException):
                with connect(write=True) as db:
                    db.execute("DELETE FROM codes WHERE token=?", (digest(token),))
                raise Problem("Could not send the verification email. Try again.", 503) from None
        return result, 200, cookie

    def apply_change(self, db, user, action, data):
        token = field(data, "request_id", 60, False)
        if not token or action == "/api/logout":
            return self.mutate(db, user, action, data)
        prior = db.execute("SELECT action,response FROM requests WHERE user_id=? AND token=?",
                           (user["id"], token)).fetchone()
        if prior:
            if prior["action"] != action:
                raise Problem("This request ID was used for a different operation.", 409)
            return json.loads(prior["response"]), None
        result, cookie = self.mutate(db, user, action, data)
        db.execute("INSERT INTO requests VALUES(?,?,?,?,?)",
                   (user["id"], token, action, json.dumps(result), now() + 86400))
        return result, cookie

    def mutate(self, db, user, action, data):
        owner = user["id"]
        body, cookie = {}, None
        if action == "/api/logout":
            db.execute("DELETE FROM sessions WHERE token=?", (self.token(),))
            cookie = ""
        elif action == "/api/bill":
            bill_id = integer_id(data["id"]) if data.get("id") is not None else None
            body["id"] = save_bill(db, owner, data, bill_id)
        elif action in ("/api/snooze", "/api/mark", "/api/pay", "/api/record", "/api/archive"):
            attempts = bill_action(db, owner, action.rsplit("/", 1)[1], data)
            if attempts:
                body["attempts"] = attempts
        elif action == "/api/schedule":
            save_schedule(db, owner, data, integer_id(data["id"]) if data.get("id") is not None else None)
        elif action == "/api/cancel":
            schedule_id = integer_id(data.get("id"))
            changed = db.execute("""UPDATE schedules SET status='Cancelled' WHERE id=? AND status='Scheduled'
                                    AND bill_id IN (SELECT id FROM bills WHERE user_id=?)""", (schedule_id, owner))
            if not changed.rowcount:
                raise Problem("Active schedule not found.", 404)
            audit(db, owner, "schedule.cancelled", schedule_id)
        elif action == "/api/refresh":
            due_schedules(db, owner)
            generate_reminders(db, owner)
        elif action == "/api/notification":
            db.execute("""UPDATE notifications SET read=1 WHERE id=? AND bill_id IN
                          (SELECT id FROM bills WHERE user_id=?)""", (integer_id(data.get("id")), owner))
        elif action == "/api/access":
            if user["role"] != "admin":
                raise Problem("Administrator access is required.", 403)
            target = integer_id(data.get("id"))
            status = choice(data, "status", ("approved", "denied"))
            changed = db.execute("UPDATE users SET status=? WHERE id=? AND status IN ('pending','denied') AND role='user'",
                                 (status, target))
            if not changed.rowcount:
                raise Problem("Access request not found.", 404)
            audit(db, owner, "access." + status, target)
        elif action in ("/api/password", "/api/pin"):
            if not verify_password(login_password(dict(password=data.get("current"))), user["password"]):
                raise Problem("Current password is incorrect.")
            if action == "/api/password":
                db.execute("UPDATE users SET password=? WHERE id=?", (new_password(data), owner))
                db.execute("DELETE FROM sessions WHERE user_id=? AND token<>?", (owner, self.token()))
                db.execute("DELETE FROM codes WHERE user_id=?", (owner,))
                audit(db, owner, "password.changed", owner)
            else:
                pin = field(data, "pin", 8, False)
                if pin and not re.fullmatch(r"[0-9]{6,8}", pin):
                    raise Problem("Use 6–8 digits for the access PIN.")
                db.execute("UPDATE users SET pin=? WHERE id=?", (password_hash(pin) if pin else None, owner))
                db.execute("DELETE FROM codes WHERE user_id=? AND purpose='login'", (owner,))
                audit(db, owner, "pin.changed", owner)
        else:
            raise Problem("Unknown action.", 404)
        return body, cookie


def tick():
    with connect(write=True) as db:
        due_schedules(db)
        for row in db.execute("SELECT id FROM users WHERE status='approved'"):
            generate_reminders(db, row["id"])
        db.execute("DELETE FROM sessions WHERE expires<=?", (now(),))
        db.execute("DELETE FROM codes WHERE expires<=?", (now(),))
        db.execute("DELETE FROM throttle WHERE expires<=?", (now(),))
        db.execute("DELETE FROM challenges WHERE expires<=?", (now(),))
        db.execute("DELETE FROM requests WHERE expires<=?", (now(),))
    with connect() as db:
        pending = db.execute("""SELECT n.id,n.content,u.email FROM notifications n JOIN bills b ON b.id=n.bill_id
                                JOIN users u ON u.id=b.user_id WHERE n.delivery='Pending email' AND
                                b.archived=0 AND u.status='approved' ORDER BY n.id""").fetchall()
    for row in pending:
        if not send_mail(row["email"], "PayPilot bill reminder", unpack(row["content"])):
            break
        with connect(write=True) as db:
            db.execute("UPDATE notifications SET delivery='Email sent' WHERE id=?", (row["id"],))


def main():
    global DEMO
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    serve = subs.add_parser("serve")
    serve.add_argument("--port", type=int, default=8000)
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--demo", action="store_true")
    admin = subs.add_parser("admin")
    admin.add_argument("username")
    admin.add_argument("email")
    subs.add_parser("tick")
    backup = subs.add_parser("backup")
    backup.add_argument("destination", type=Path)
    args = parser.parse_args()
    initialize()
    if args.command == "serve":
        DEMO = args.demo
        if DEMO and args.host != "127.0.0.1":
            parser.error("Demo mode requires binding to 127.0.0.1.")
        with ThreadingHTTPServer((args.host, args.port), Handler) as server:
            print(f"PayPilot is running at http://{args.host}:{server.server_port}", flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass
    elif args.command == "admin":
        username, email = credentials(vars(args))
        password = new_password(dict(password=getpass("Administrator password: "),
                                     confirm=getpass("Confirm password: ")))
        try:
            with connect(write=True) as db:
                owner = db.execute("""INSERT INTO users(username,email,password,status,role)
                                      VALUES(?,?,?,'approved','admin')""", (username, email, password)).lastrowid
                audit(db, owner, "admin.created", owner)
        except sqlite3.IntegrityError:
            parser.error("The user ID or email is already in use.")
        print("Administrator created.")
    elif args.command == "tick":
        tick()
    else:
        with args.destination.open("xb"):
            pass
        source = sqlite3.connect(DATA / "app.sqlite3")
        destination = sqlite3.connect(args.destination)
        try:
            source.backup(destination)
        finally:
            destination.close()
            source.close()
        print("Database backup created. Store the encryption key separately.")


if __name__ == "__main__":
    main()
