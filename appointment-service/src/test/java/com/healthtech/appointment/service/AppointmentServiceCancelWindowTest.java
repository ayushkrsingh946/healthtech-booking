package com.healthtech.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.config.ChangePolicyProperties;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.exception.AppointmentAccessDeniedException;
import com.healthtech.appointment.exception.CancellationWindowClosedException;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.outbox.OutboxEventWriter;
import com.healthtech.appointment.outbox.OutboxRepository;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.healthtech.appointment.domain.AppointmentType.INITIAL_CONSULTATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Unit tests for the notice period of AppointmentService.cancelAppointment, with a fixed clock.
@ExtendWith(MockitoExtension.class)
class AppointmentServiceCancelWindowTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private AppointmentMapper appointmentMapper;

    @Mock
    private ValidPatientRepository validPatientRepository;

    @Mock
    private ValidDoctorRepository validDoctorRepository;

    @Mock
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final UUID patientId = UUID.randomUUID();

    private AppointmentService serviceWithNotice(int minNoticeHours) {
        return new AppointmentService(appointmentRepository, appointmentMapper, validPatientRepository,
                validDoctorRepository, List.of(), new OutboxEventWriter(outboxRepository, objectMapper), CLOCK,
                new ChangePolicyProperties(minNoticeHours));
    }

    private Appointment appointmentAt(LocalDateTime dateTime, AppointmentStatus status) {
        return Appointment.builder()
                .id(UUID.randomUUID())
                .patientId(patientId)
                .doctorId(UUID.randomUUID())
                .dateTime(dateTime)
                .duration(30)
                .type(INITIAL_CONSULTATION)
                .status(status)
                .build();
    }

    private void stubLoaded(Appointment appointment) {
        when(appointmentRepository.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
    }

    private void stubSaveAndResponse(Appointment appointment) {
        when(appointmentRepository.save(appointment)).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());
    }

    private void assertNothingSavedOrPublished() {
        verify(appointmentRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void cancelAppointment_startsExactlyAtMinimumNotice_isCancelled() {
        // Arrange
        Appointment appointment = appointmentAt(NOW.plusHours(48), AppointmentStatus.CONFIRMED);
        stubLoaded(appointment);
        stubSaveAndResponse(appointment);

        // Act
        serviceWithNotice(48).cancelAppointment(appointment.getId(), patientId);

        // Assert
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void cancelAppointment_startsOneMinuteInsideMinimumNotice_throwsCancellationWindowClosed() {
        // Arrange
        Appointment appointment = appointmentAt(NOW.plusHours(48).minusMinutes(1), AppointmentStatus.CONFIRMED);
        stubLoaded(appointment);

        // Act & Assert
        assertThatThrownBy(() -> serviceWithNotice(48).cancelAppointment(appointment.getId(), patientId))
                .isInstanceOf(CancellationWindowClosedException.class)
                .hasMessageContaining("48 hours");
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertNothingSavedOrPublished();
    }

    @Test
    void cancelAppointment_alreadyStarted_throwsCancellationWindowClosed() {
        // Arrange
        Appointment appointment = appointmentAt(NOW.minusMinutes(10), AppointmentStatus.CONFIRMED);
        stubLoaded(appointment);

        // Act & Assert
        assertThatThrownBy(() -> serviceWithNotice(48).cancelAppointment(appointment.getId(), patientId))
                .isInstanceOf(CancellationWindowClosedException.class);
        assertNothingSavedOrPublished();
    }

    @Test
    void cancelAppointment_configuredNoticeOfSixHours_cancelsAppointmentSixHoursAway() {
        // Arrange
        Appointment appointment = appointmentAt(NOW.plusHours(6), AppointmentStatus.CONFIRMED);
        stubLoaded(appointment);
        stubSaveAndResponse(appointment);

        // Act
        serviceWithNotice(6).cancelAppointment(appointment.getId(), patientId);

        // Assert
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void cancelAppointment_configuredNoticeOfSixHours_refusesAppointmentOneMinuteInside() {
        // Arrange
        Appointment appointment = appointmentAt(NOW.plusHours(6).minusMinutes(1), AppointmentStatus.CONFIRMED);
        stubLoaded(appointment);

        // Act & Assert
        assertThatThrownBy(() -> serviceWithNotice(6).cancelAppointment(appointment.getId(), patientId))
                .isInstanceOf(CancellationWindowClosedException.class)
                .hasMessageContaining("6 hours");
        assertNothingSavedOrPublished();
    }

    @Test
    void cancelAppointment_alreadyCancelledInsideNoticePeriod_returnsCurrentStateWithoutError() {
        // Arrange: the repeat cancel stays a no-op, so the cancelled check comes before the notice check
        Appointment appointment = appointmentAt(NOW.plusHours(1), AppointmentStatus.CANCELLED);
        stubLoaded(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(
                AppointmentResponse.builder().status(AppointmentStatus.CANCELLED).build());

        // Act
        AppointmentResponse result = serviceWithNotice(48).cancelAppointment(appointment.getId(), patientId);

        // Assert
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertNothingSavedOrPublished();
    }

    @Test
    void cancelAppointment_otherPatientsAppointmentInsideNoticePeriod_throwsAccessDenied() {
        // Arrange: ownership is checked before the notice period, so a non-owner learns nothing about the time
        Appointment appointment = appointmentAt(NOW.plusHours(1), AppointmentStatus.CONFIRMED);
        stubLoaded(appointment);

        // Act & Assert
        assertThatThrownBy(() -> serviceWithNotice(48).cancelAppointment(appointment.getId(), UUID.randomUUID()))
                .isInstanceOf(AppointmentAccessDeniedException.class);
        assertNothingSavedOrPublished();
    }
}
