package com.hotel.hotel_app;

// ============================================================
//  App.java  –  Single-file Spring Boot Hotel Management API
//  Equivalent of the original Flask app.py
//
//  Dependencies (add to pom.xml):
//    spring-boot-starter-web
//    spring-boot-starter-jdbc
//    mysql-connector-j
//
//  application.properties (src/main/resources/):
//    spring.datasource.url=jdbc:mysql://localhost:3306/HotelDB
//    spring.datasource.username=root
//    spring.datasource.password=root
//    server.port=5000
//
//  Run:  mvn spring-boot:run
// ============================================================

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// ──────────────────────────────────────────────────────────────
// 1. Entry Point
// ──────────────────────────────────────────────────────────────
@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}

// ──────────────────────────────────────────────────────────────
// 2. CORS  (replaces Flask-CORS)
// ──────────────────────────────────────────────────────────────
@Configuration
class CorsConfig {
    @Bean
    WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry r) {
                r.addMapping("/**").allowedOrigins("*").allowedMethods("*");
            }
        };
    }
}

// ──────────────────────────────────────────────────────────────
// 3. Base  –  shared helpers every controller extends
// ──────────────────────────────────────────────────────────────
class Base {

    final JdbcTemplate db;

    Base(JdbcTemplate db) { this.db = db; }

    /** Returns a 500 JSON error response. */
    ResponseEntity<Map<String, Object>> error(Exception e) {
        return ResponseEntity.internalServerError()
                .body(Map.of("success", false,
                             "message", e.getMessage() != null ? e.getMessage() : "Unknown error"));
    }

    /** Converts the current row of a ResultSet to a LinkedHashMap. */
    Map<String, Object> toMap(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++)
            row.put(meta.getColumnLabel(i), rs.getObject(i));
        return row;
    }

    /** Drains all rows from an open ResultSet into a List. */
    List<Map<String, Object>> toList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        while (rs.next()) list.add(toMap(rs));
        return list;
    }

    /** Opens a raw JDBC Connection (for stored-procedure calls). */
    Connection conn() throws SQLException {
        return Objects.requireNonNull(db.getDataSource()).getConnection();
    }
}

// ──────────────────────────────────────────────────────────────
// 4. AUTH  –  /login  /users
// ──────────────────────────────────────────────────────────────
@RestController
class AuthController extends Base {

    AuthController(JdbcTemplate db) { super(db); }

    /** POST /login  –  delegates to stored proc LoginUser(username, password) */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        try (Connection c = conn();
             CallableStatement cs = c.prepareCall("{CALL LoginUser(?, ?)}")) {
            cs.setString(1, body.get("username"));
            cs.setString(2, body.get("password"));
            Map<String, Object> user = null;
            if (cs.execute()) {
                try (ResultSet rs = cs.getResultSet()) {
                    if (rs.next()) user = toMap(rs);
                }
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", user != null);
            resp.put("user", user);
            return ResponseEntity.ok(resp);
        } catch (Exception e) { return error(e); }
    }

    /** POST /users  –  create user */
    @PostMapping("/users")
    public ResponseEntity<?> addUser(@RequestBody Map<String, String> body) {
        try {
            db.update(
                "INSERT INTO Users (username, password, email, role) VALUES (?, ?, ?, ?)",
                body.get("username"), body.get("password"), body.get("email"), body.get("role"));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) { return error(e); }
    }

    /** GET /users  –  list non-admin users */
    @GetMapping("/users")
    public ResponseEntity<?> getUsers() {
        return ResponseEntity.ok(db.queryForList(
            "SELECT user_id, username, email, role FROM Users WHERE role != 'Admin'"));
    }

    /** PUT /users/{id}  –  update one field (whitelisted) */
    @PutMapping("/users/{userId}")
    public ResponseEntity<?> updateUser(@PathVariable int userId,
                                        @RequestBody Map<String, String> body) {
        List<String> allowed = List.of("username", "password", "email", "role");
        String field = body.get("field");
        if (!allowed.contains(field))
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid field"));
        try {
            db.update("UPDATE Users SET " + field + " = ? WHERE user_id = ?", body.get("value"), userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) { return error(e); }
    }

    /** DELETE /users/{id} */
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable int userId) {
        try {
            db.update("DELETE FROM Users WHERE user_id = ?", userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) { return error(e); }
    }
}

// ──────────────────────────────────────────────────────────────
// 5. GUESTS  –  /guests  /all-guests
// ──────────────────────────────────────────────────────────────
@RestController
class GuestController extends Base {

    GuestController(JdbcTemplate db) { super(db); }

    /** GET /guests[?fields=minimal] */
    @GetMapping("/guests")
    public ResponseEntity<?> getGuests(@RequestParam(required = false) String fields) {
        String sql = "minimal".equals(fields)
            ? "SELECT guest_id, first_name, last_name FROM Guests WHERE is_deleted = 0"
            : "SELECT * FROM Guests WHERE is_deleted = 0";
        return ResponseEntity.ok(db.queryForList(sql));
    }

    /** POST /guests */
    @PostMapping("/guests")
    public ResponseEntity<?> addGuest(@RequestBody Map<String, String> body) {
        try {
            db.update("""
                INSERT INTO Guests (first_name, last_name, phone, email, address, id_proof, id_number)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                body.get("first_name"), body.get("last_name"), body.get("phone"),
                body.get("email"), body.get("address"), body.get("id_proof"), body.get("id_number"));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) { return error(e); }
    }

    /** GET /guests/{id} */
    @GetMapping("/guests/{guestId}")
    public ResponseEntity<?> getGuest(@PathVariable int guestId) {
        List<Map<String, Object>> rows =
            db.queryForList("SELECT * FROM Guests WHERE guest_id = ?", guestId);
        return rows.isEmpty()
            ? ResponseEntity.ok(Map.of("success", false))
            : ResponseEntity.ok(rows.get(0));
    }

    /** PUT /guests/{id} */
    @PutMapping("/guests/{guestId}")
    public ResponseEntity<?> updateGuest(@PathVariable int guestId,
                                          @RequestBody Map<String, String> body) {
        try {
            db.update("""
                UPDATE Guests SET
                  first_name=?, last_name=?, phone=?, email=?,
                  address=?, id_proof=?, id_number=?
                WHERE guest_id=?""",
                body.get("first_name"), body.get("last_name"), body.get("phone"),
                body.get("email"), body.get("address"),
                body.get("id_proof"), body.get("id_number"), guestId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) { return error(e); }
    }

    /** DELETE /guests/{id}  –  soft delete */
    @DeleteMapping("/guests/{guestId}")
    public ResponseEntity<?> deleteGuest(@PathVariable int guestId) {
        try {
            db.update("UPDATE Guests SET is_deleted = 1 WHERE guest_id = ?", guestId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) { return error(e); }
    }

    /** GET /all-guests  –  includes soft-deleted rows (admin view) */
    @GetMapping("/all-guests")
    public ResponseEntity<?> allGuests() {
        return ResponseEntity.ok(db.queryForList("""
            SELECT guest_id, first_name, last_name, phone, email,
                   address, id_proof, id_number, is_deleted
            FROM Guests ORDER BY guest_id DESC"""));
    }
}

// ──────────────────────────────────────────────────────────────
// 6. PAYMENTS  –  /bill  /payments  /unpaid-reservations
// ──────────────────────────────────────────────────────────────
@RestController
class PaymentController extends Base {

    PaymentController(JdbcTemplate db) { super(db); }

    /** GET /bill/{guestId} */
    @GetMapping("/bill/{guestId}")
    public ResponseEntity<?> getGuestBill(@PathVariable int guestId) {
        try {
            List<Map<String, Object>> rows = db.queryForList("""
                SELECT
                    r.reservation_id,
                    CONCAT(g.first_name, ' ', g.last_name)            AS guest_name,
                    rm.room_number,
                    rt.base_price,
                    r.check_in,
                    r.check_out,
                    DATEDIFF(r.check_out, r.check_in)                  AS nights,
                    r.total_amount,
                    CASE WHEN p.reservation_id IS NOT NULL
                         THEN 'Paid' ELSE 'Pending' END                AS payment_status
                FROM Reservations r
                JOIN Guests    g  ON r.guest_id  = g.guest_id
                JOIN Rooms     rm ON r.room_id   = rm.room_id
                JOIN RoomTypes rt ON rm.type_id  = rt.type_id
                LEFT JOIN Payments p ON r.reservation_id = p.reservation_id
                WHERE g.guest_id = ?
                ORDER BY r.check_in DESC LIMIT 1""", guestId);
            return rows.isEmpty() ? ResponseEntity.ok(Map.of()) : ResponseEntity.ok(rows.get(0));
        } catch (Exception e) { return error(e); }
    }

    /** GET /payments */
    @GetMapping("/payments")
    public ResponseEntity<?> getPayments() {
        return ResponseEntity.ok(db.queryForList("""
            SELECT payment_id, reservation_id, amount, method, payment_type, DATE(paid_at) AS date
            FROM Payments ORDER BY paid_at"""));
    }

    /** GET /unpaid-reservations */
    @GetMapping("/unpaid-reservations")
    public ResponseEntity<?> unpaidReservations() {
        return ResponseEntity.ok(db.queryForList("""
            SELECT r.reservation_id, g.first_name, g.last_name, r.total_amount
            FROM Reservations r
            JOIN Guests g ON r.guest_id = g.guest_id
            WHERE r.reservation_id NOT IN (SELECT reservation_id FROM Payments)
              AND r.status IN ('Confirmed', 'Checked-In')"""));
    }
}

// ──────────────────────────────────────────────────────────────
// 7. GUEST SERVICES  –  /guest-services
// ──────────────────────────────────────────────────────────────
@RestController
class GuestServiceController extends Base {

    GuestServiceController(JdbcTemplate db) { super(db); }

    /** GET /guest-services */
    @GetMapping("/guest-services")
    public ResponseEntity<?> getGuestServices() {
        return ResponseEntity.ok(db.queryForList("""
            SELECT gs.id, gs.reservation_id, s.name AS service_name,
                   CONCAT(g.first_name, ' ', g.last_name) AS guest_name,
                   gs.quantity, DATE(gs.date) AS date
            FROM GuestServices gs
            JOIN Services     s  ON gs.service_id     = s.service_id
            JOIN Reservations r  ON gs.reservation_id = r.reservation_id
            JOIN Guests       g  ON r.guest_id        = g.guest_id
            ORDER BY gs.date DESC"""));
    }
}

// ──────────────────────────────────────────────────────────────
// 8. ROOMS  –  /rooms/available  /rooms/all  /room-types  /rooms/by-type
// ──────────────────────────────────────────────────────────────
@RestController
class RoomController extends Base {

    RoomController(JdbcTemplate db) { super(db); }

    /** POST /rooms/available  –  calls stored proc GetAvailableRooms */
    @PostMapping("/rooms/available")
    public ResponseEntity<?> getAvailableRooms(@RequestBody Map<String, Object> body) {
        try (Connection c = conn();
             CallableStatement cs = c.prepareCall("{CALL GetAvailableRooms(?, ?, ?)}")) {
            cs.setString(1, (String) body.get("check_in"));
            cs.setString(2, (String) body.get("check_out"));
            cs.setInt(3, body.containsKey("guests") ? ((Number) body.get("guests")).intValue() : 1);
            List<Map<String, Object>> rooms = new ArrayList<>();
            if (cs.execute()) {
                try (ResultSet rs = cs.getResultSet()) { rooms = toList(rs); }
            }
            return ResponseEntity.ok(rooms);
        } catch (Exception e) { return error(e); }
    }

    /** GET /rooms/all */
    @GetMapping("/rooms/all")
    public ResponseEntity<?> allRooms() {
        return ResponseEntity.ok(db.queryForList("""
            SELECT r.room_number, rt.name AS room_type, r.status,
                   CONCAT(g.first_name, ' ', g.last_name) AS guest_name,
                   res.check_in, res.check_out
            FROM Rooms r
            JOIN RoomTypes rt ON r.type_id = rt.type_id
            LEFT JOIN Reservations res ON r.room_id = res.room_id
                AND res.status IN ('Confirmed', 'Checked-In')
            LEFT JOIN Guests g ON res.guest_id = g.guest_id
            ORDER BY res.check_in DESC"""));
    }

    /** GET /room-types */
    @GetMapping("/room-types")
    public ResponseEntity<?> roomTypes() {
        return ResponseEntity.ok(db.queryForList("SELECT DISTINCT name FROM RoomTypes"));
    }

    /** GET /rooms/by-type/{bedType} */
    @GetMapping("/rooms/by-type/{bedType}")
    public ResponseEntity<?> roomsByType(@PathVariable String bedType) {
        return ResponseEntity.ok(db.queryForList("""
            SELECT r.room_id, r.room_number, r.status,
                   CONCAT(g.first_name, ' ', g.last_name) AS guest_name,
                   res.check_in, res.check_out
            FROM Rooms r
            JOIN RoomTypes rt ON r.type_id = rt.type_id
            LEFT JOIN Reservations res ON r.room_id = res.room_id
                AND res.status IN ('Confirmed', 'Checked-In')
            LEFT JOIN Guests g ON res.guest_id = g.guest_id
            WHERE rt.name = ?
            ORDER BY r.room_number LIMIT 25""", bedType));
    }
}

// ──────────────────────────────────────────────────────────────
// 9. RESERVATIONS  –  /reservations  /assign-room  /unassign-room
// ──────────────────────────────────────────────────────────────
@RestController
class ReservationController extends Base {

    ReservationController(JdbcTemplate db) { super(db); }

    /** POST /reservations  –  calculate price and create reservation */
    @PostMapping("/reservations")
    public ResponseEntity<?> createReservation(@RequestBody Map<String, Object> body) {
        try {
            int roomId = ((Number) body.get("room_id")).intValue();

            List<Map<String, Object>> priceRows = db.queryForList(
                "SELECT base_price FROM RoomTypes WHERE type_id = " +
                "(SELECT type_id FROM Rooms WHERE room_id = ?)", roomId);
            if (priceRows.isEmpty())
                return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Room not found"));

            double basePrice   = ((Number) priceRows.get(0).get("base_price")).doubleValue();
            LocalDate checkIn  = LocalDate.parse((String) body.get("check_in"));
            LocalDate checkOut = LocalDate.parse((String) body.get("check_out"));
            long nights        = ChronoUnit.DAYS.between(checkIn, checkOut);
            double total       = basePrice * nights;

            db.update("""
                INSERT INTO Reservations
                  (guest_id, room_id, receptionist_id, check_in, check_out, adults, children, status, total_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Confirmed', ?)""",
                ((Number) body.get("guest_id")).intValue(),
                roomId,
                ((Number) body.get("receptionist_id")).intValue(),
                body.get("check_in"), body.get("check_out"),
                body.containsKey("adults")   ? ((Number) body.get("adults")).intValue()   : 1,
                body.containsKey("children") ? ((Number) body.get("children")).intValue() : 0,
                total);

            db.update("UPDATE Rooms SET status = 'Occupied' WHERE room_id = ?", roomId);
            return ResponseEntity.ok(Map.of("success", true, "total_amount", total));
        } catch (Exception e) { return error(e); }
    }

    /** POST /reservations/{id}/checkin */
    @PostMapping("/reservations/{reservationId}/checkin")
    public ResponseEntity<?> checkIn(@PathVariable int reservationId) {
        try {
            db.update("UPDATE Reservations SET status = 'Checked-In' WHERE reservation_id = ?",
                      reservationId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) { return error(e); }
    }

    /** POST /reservations/{id}/checkout  –  status + free room + record payment */
    @PostMapping("/reservations/{reservationId}/checkout")
    public ResponseEntity<?> checkOut(@PathVariable int reservationId) {
        try {
            List<Map<String, Object>> rows = db.queryForList(
                "SELECT room_id, total_amount FROM Reservations WHERE reservation_id = ?",
                reservationId);
            if (rows.isEmpty())
                return ResponseEntity.ok(Map.of("success", false, "message", "Reservation not found"));

            int    roomId = ((Number) rows.get(0).get("room_id")).intValue();
            double total  = ((Number) rows.get(0).get("total_amount")).doubleValue();

            db.update("UPDATE Reservations SET status = 'Checked-Out' WHERE reservation_id = ?",
                      reservationId);
            db.update("UPDATE Rooms SET status = 'Available' WHERE room_id = ?", roomId);
            db.update("INSERT INTO Payments (reservation_id, amount, method, payment_type) VALUES (?, ?, 'Cash', 'Full')",
                      reservationId, total);

            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) { return error(e); }
    }

    /** POST /assign-room  –  quick assign shortcut */
    @PostMapping("/assign-room")
    public ResponseEntity<?> assignRoom(@RequestBody Map<String, Object> body) {
        try {
            int    roomId  = ((Number) body.get("room_id")).intValue();
            int    guestId = ((Number) body.get("guest_id")).intValue();
            String checkIn = (String) body.get("check_in");
            String checkOut= (String) body.get("check_out");
            int    recId   = 1; // temp hardcoded – same as original Flask code

            List<Map<String, Object>> priceRows = db.queryForList(
                "SELECT base_price FROM RoomTypes WHERE type_id = " +
                "(SELECT type_id FROM Rooms WHERE room_id = ?)", roomId);
            double basePrice = ((Number) priceRows.get(0).get("base_price")).doubleValue();

            db.update("""
                INSERT INTO Reservations
                  (guest_id, room_id, receptionist_id, check_in, check_out, adults, children, status, total_amount)
                VALUES (?, ?, ?, ?, ?, 1, 0, 'Confirmed', ?)""",
                guestId, roomId, recId, checkIn, checkOut, basePrice);

            db.update("UPDATE Rooms SET status = 'Occupied' WHERE room_id = ?", roomId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) { return error(e); }
    }

    /** POST /unassign-room  –  cancel latest active reservation, free room */
    @PostMapping("/unassign-room")
    public ResponseEntity<?> unassignRoom(@RequestBody Map<String, Object> body) {
        try {
            int roomId = ((Number) body.get("room_id")).intValue();
            db.update("UPDATE Rooms SET status = 'Available' WHERE room_id = ?", roomId);
            db.update("""
                UPDATE Reservations SET status = 'Cancelled'
                WHERE room_id = ? AND status IN ('Confirmed', 'Checked-In')
                ORDER BY check_in DESC LIMIT 1""", roomId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) { return error(e); }
    }
  @org.springframework.stereotype.Controller
class PageController {

    @GetMapping("/")
    public String root() {
        return "redirect:/login.html";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "forward:/login.html";
    }

    @GetMapping("/dashboard")
    public String dashboardPage() {
        return "forward:/dashboard.html";
    }

    @GetMapping("/admin")
    public String adminPage() {
        return "forward:/admin.html";
    }

    @GetMapping("/manager")
    public String managerPage() {
        return "forward:/manager_dashboard.html";
    }
}
}
