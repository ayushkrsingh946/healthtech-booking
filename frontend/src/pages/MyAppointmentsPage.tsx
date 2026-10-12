import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { getMyAppointments, cancelAppointment } from "../api/appointments";
import { getDoctor } from "../api/doctors";
import { ApiError } from "../api/client";
import { ChangeAppointmentForm } from "../components/ChangeAppointmentForm";
import { NoticeDialog } from "../components/NoticeDialog";
import { StatusMessage } from "../components/StatusMessage";
import { CHANGE_MIN_NOTICE_HOURS } from "../api/constants";
import { isWithinNoticePeriod } from "../dates";
import type { AppointmentResponse } from "../api/types";

interface DoctorContact {
  name: string;
  // Absent when the doctor could not be loaded.
  phoneNumber?: string;
}

// The appointment a change or cancel was refused for because it starts inside the notice period.
interface BlockedAction {
  action: "change" | "cancel";
  doctorId: string;
}

export function MyAppointmentsPage() {
  const [appointments, setAppointments] = useState<AppointmentResponse[]>([]);
  const [doctors, setDoctors] = useState<Record<string, DoctorContact>>({});
  const [status, setStatus] = useState<"loading" | "success" | "error">("loading");
  const [error, setError] = useState("");
  const [cancellingId, setCancellingId] = useState<string | null>(null);
  const [changingId, setChangingId] = useState<string | null>(null);
  // Shown above the list: the outcome of the last cancel or change.
  const [actionError, setActionError] = useState("");
  const [notice, setNotice] = useState("");
  const [blocked, setBlocked] = useState<BlockedAction | null>(null);

  function load() {
    setStatus("loading");
    getMyAppointments()
      .then(async (results) => {
        setAppointments(results);
        setStatus("success");

        const uniqueDoctorIds = [...new Set(results.map((a) => a.doctorId))];
        const entries = await Promise.all(
          uniqueDoctorIds.map(async (doctorId) => {
            try {
              const doctor = await getDoctor(doctorId);
              const contact: DoctorContact = {
                name: `Dr. ${doctor.firstName} ${doctor.lastName}`,
                phoneNumber: doctor.phoneNumber,
              };
              return [doctorId, contact] as const;
            } catch {
              const contact: DoctorContact = { name: doctorId };
              return [doctorId, contact] as const;
            }
          }),
        );
        setDoctors(Object.fromEntries(entries));
      })
      .catch((err) => {
        setError(err instanceof ApiError ? err.message : "Could not load your appointments.");
        setStatus("error");
      });
  }

  useEffect(load, []);

  function block(action: BlockedAction["action"], appointment: AppointmentResponse) {
    setChangingId(null);
    setBlocked({ action, doctorId: appointment.doctorId });
  }

  async function handleCancel(appointment: AppointmentResponse) {
    setActionError("");
    setNotice("");
    if (isWithinNoticePeriod(appointment.dateTime)) {
      block("cancel", appointment);
      return;
    }
    setCancellingId(appointment.id);
    try {
      await cancelAppointment(appointment.id);
      setChangingId(null);
      load();
    } catch (err) {
      if (err instanceof ApiError && err.title === "Cancellation Window Closed") {
        block("cancel", appointment);
      } else {
        setActionError(err instanceof ApiError ? err.message : "Could not cancel this appointment.");
      }
    } finally {
      setCancellingId(null);
    }
  }

  function handleChangeClick(appointment: AppointmentResponse) {
    setActionError("");
    setNotice("");
    if (isWithinNoticePeriod(appointment.dateTime)) {
      block("change", appointment);
      return;
    }
    setChangingId(changingId === appointment.id ? null : appointment.id);
  }

  function handleChanged() {
    setChangingId(null);
    setActionError("");
    setNotice("Appointment changed.");
    load();
  }

  if (status === "loading") {
    return <StatusMessage kind="loading">Loading your appointments...</StatusMessage>;
  }
  if (status === "error") {
    return <StatusMessage kind="error">{error}</StatusMessage>;
  }

  return (
    <div>
      <h1>My appointments</h1>
      {notice && <StatusMessage kind="info">{notice}</StatusMessage>}
      {actionError && <StatusMessage kind="error">{actionError}</StatusMessage>}
      {appointments.length === 0 && <StatusMessage kind="info">You have no appointments yet.</StatusMessage>}
      {blocked && (
        <WindowClosedDialog
          blocked={blocked}
          doctor={doctors[blocked.doctorId]}
          onClose={() => setBlocked(null)}
        />
      )}
      <ul className="appointment-list">
        {appointments.map((appointment) => (
          <li key={appointment.id} className={appointment.status === "CANCELLED" ? "cancelled" : undefined}>
            <span>{doctors[appointment.doctorId]?.name ?? appointment.doctorId}</span>
            <span>{appointment.dateTime.replace("T", " ")}</span>
            <span>{appointment.type}</span>
            <span>{appointment.status}</span>
            {appointment.status !== "CANCELLED" && (
              <>
                <button
                  type="button"
                  onClick={() => handleChangeClick(appointment)}
                  disabled={cancellingId === appointment.id}
                >
                  Change
                </button>
                <button
                  type="button"
                  onClick={() => handleCancel(appointment)}
                  disabled={cancellingId === appointment.id}
                >
                  {cancellingId === appointment.id ? "Cancelling..." : "Cancel"}
                </button>
              </>
            )}
            {changingId === appointment.id && (
              <ChangeAppointmentForm
                appointment={appointment}
                onSaved={handleChanged}
                onClose={() => setChangingId(null)}
                onWindowClosed={() => block("change", appointment)}
              />
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

interface WindowClosedDialogProps {
  blocked: BlockedAction;
  // Undefined while the doctors are still loading.
  doctor: DoctorContact | undefined;
  onClose: () => void;
}

function WindowClosedDialog({ blocked, doctor, onClose }: WindowClosedDialogProps) {
  const verb = blocked.action === "change" ? "changed" : "cancelled";
  return (
    <NoticeDialog title={`This appointment can no longer be ${verb}`} onClose={onClose}>
      <p>
        Appointments can only be {verb} up to {CHANGE_MIN_NOTICE_HOURS} hours before they start, and this one starts in
        less than {CHANGE_MIN_NOTICE_HOURS} hours.
      </p>
      {doctor?.phoneNumber ? (
        <p>
          Please call the clinic of {doctor.name} at <a href={`tel:${doctor.phoneNumber}`}>{doctor.phoneNumber}</a>.
        </p>
      ) : (
        <p>
          Please contact the clinic. Its phone number could not be loaded; you can find it on the{" "}
          <Link to={`/doctors/${blocked.doctorId}`}>doctor's page</Link>.
        </p>
      )}
    </NoticeDialog>
  );
}
