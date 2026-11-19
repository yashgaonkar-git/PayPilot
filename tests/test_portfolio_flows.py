"""Exercise the payment ledger and reminder inbox with isolated demo data."""
from datetime import date
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import patch

import paypilot


class PortfolioFlows(TestCase):
    def setUp(self):
        folder = TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        for name, value in (("DATA", Path(folder.name)), ("CIPHER", None), ("OTP_KEY", None)):
            original = patch.object(paypilot, name, value)
            original.start()
            self.addCleanup(original.stop)
        paypilot.initialize()
        with paypilot.connect(write=True) as db:
            paypilot.create_demo(db)
            self.owner = db.execute("SELECT * FROM users").fetchone()

    def test_decline_preserves_balance_and_retry_settles_once(self):
        with paypilot.connect(write=True) as db:
            electricity = next(b for b in paypilot.bills_for(db, self.owner["id"]) if b["name"] == "Electricity")
            bill = electricity["id"]
            handler = object.__new__(paypilot.Handler)
            data = dict(ids=[bill], method="Digital wallet", institution="Demo Wallet")
            declined, _ = handler.apply_change(db, self.owner, "/api/pay",
                                               dict(data, outcome="Decline", request_id="first"))
            self.assertEqual(declined["attempts"][0]["status"], "Declined")
            self.assertEqual(paypilot.account_amounts(db, bill), (86000, 0))

            request = dict(data, outcome="Approve", request_id="retry")
            settled, _ = handler.apply_change(db, self.owner, "/api/pay", request)
            replay, _ = handler.apply_change(db, self.owner, "/api/pay", request)
            self.assertEqual(settled, replay)
            self.assertEqual(paypilot.account_amounts(db, bill), (216000, 0))
            state = paypilot.state(db, self.owner)
            self.assertEqual([a["status"] for a in state["payment_attempts"]], ["Settled", "Declined"])
            self.assertEqual(state["payment_attempts"][0]["institution"], "Demo Wallet")
            self.assertNotEqual(settled["attempts"][0]["reference"], declined["attempts"][0]["reference"])

    def test_scheduled_settlement_creates_one_receipt(self):
        with paypilot.connect(write=True) as db:
            owner = self.owner["id"]
            schedule = paypilot.schedule_rows(db, owner)[0]
            paypilot.save_schedule(db, owner, {"day": date.today().isoformat()}, schedule["id"])
            paypilot.due_schedules(db, owner)
            paypilot.due_schedules(db, owner)
            state = paypilot.state(db, self.owner)
            self.assertEqual(state["schedules"][0]["status"], "Completed")
            self.assertEqual(len(state["payment_attempts"]), 1)
            self.assertEqual(state["payment_attempts"][0]["source"], "Simulated schedule")

    def test_receipt_is_visible_only_to_its_owner(self):
        with paypilot.connect(write=True) as db:
            owner = self.owner["id"]
            bill = next(b for b in paypilot.bills_for(db, owner) if b["name"] == "Electricity")
            paypilot.bill_action(db, owner, "pay", dict(ids=[bill["id"]], method="Bank transfer"))
            paypilot.create_demo(db)
            another_owner = db.execute("SELECT * FROM users WHERE id<>?", (owner,)).fetchone()
            self.assertEqual(paypilot.state(db, another_owner)["payment_attempts"], [])
            self.assertEqual(len(paypilot.state(db, self.owner)["payment_attempts"]), 1)

    def test_contact_reminder_is_a_single_sms_preview(self):
        with paypilot.connect(write=True) as db:
            owner = self.owner["id"]
            bill = next(b for b in paypilot.bills_for(db, owner) if b["name"] == "Music subscription")
            paypilot.save_bill(db, owner, dict(name=bill["name"], company=bill["company"], due=bill["due"],
                                               category=bill["category"], amount="149", priority="Normal",
                                               payment_frequency="Once", reminder_frequency="Daily",
                                               reminder_start=date.today().isoformat(), channel="Contact",
                                               reminder_message="Your music subscription is coming up."), bill["id"])
            paypilot.generate_reminders(db, owner)
            paypilot.generate_reminders(db, owner)
            notes = paypilot.state(db, self.owner)["notifications"]
            previews = [note for note in notes if note["message"] == "Your music subscription is coming up."]
            self.assertEqual(len(previews), 1)
            self.assertEqual(previews[0]["delivery"], paypilot.SMS_PREVIEW)
