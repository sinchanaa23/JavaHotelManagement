// dashboard.js

let guestsData = [];

function showSection(section) {
  document.querySelectorAll('.section').forEach(s => s.classList.add('hidden'));
  const target = document.getElementById(`${section}-section`);
  if (target) target.classList.remove('hidden');

  if (section === 'guests') loadGuests();
  if (section === 'rooms') loadRoomTable();
}

function logout() {
  localStorage.clear();
  window.location.href = "/";
}

function loadGuests() {
  fetch("/guests")
    .then(res => res.json())
    .then(data => {
      guestsData = data;
      const tbody = document.getElementById("guestTableBody");
      tbody.innerHTML = "";
      data.forEach(guest => {
        const row = document.createElement("tr");
        row.innerHTML = `
          <td>${guest.guest_id}</td>
          <td>${guest.first_name} ${guest.last_name}</td>
          <td>${guest.phone}</td>
          <td>${guest.email || ''}</td>
          <td>${guest.id_proof || ''} - ${guest.id_number || ''}</td>
          <td>${guest.address || ''}</td>
          <td>
            <button onclick="editGuest(${guest.guest_id})">Edit</button>
            <button onclick="deleteGuest(${guest.guest_id})">Delete</button>
          </td>
        `;
        tbody.appendChild(row);
      });
    });
}

function loadGuestDropdown() {
  fetch("/guests?fields=minimal")
    .then(res => res.json())
    .then(data => {
      const guestSelect = document.getElementById("guestSelect");
      guestSelect.innerHTML = "<option disabled selected>Select a guest</option>";
      data.forEach(g => {
        const option = document.createElement("option");
        option.value = g.guest_id;
        option.textContent = `${g.first_name} ${g.last_name}`;
        guestSelect.appendChild(option);
      });
    });
}

function addGuest() {
  const data = {
    first_name: document.getElementById("guestFName").value,
    last_name: document.getElementById("guestLName").value,
    phone: document.getElementById("guestPhone").value,
    email: document.getElementById("guestEmail").value,
    address: document.getElementById("guestAddress").value,
    id_proof: document.getElementById("guestIdProof").value,
    id_number: document.getElementById("guestIdNumber").value
  };
  fetch("/guests", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data)
  }).then(res => res.json()).then(result => {
    if (result.success) {
      alert("Guest added successfully!");
      loadGuests();
    } else {
      alert("Failed to add guest.");
    }
  });
}

function editGuest(guest_id) {
  const guest = guestsData.find(g => g.guest_id === guest_id);
  if (!guest) return alert("Guest not found!");

  document.getElementById("guestFName").value = guest.first_name;
  document.getElementById("guestLName").value = guest.last_name;
  document.getElementById("guestPhone").value = guest.phone;
  document.getElementById("guestEmail").value = guest.email || "";
  document.getElementById("guestAddress").value = guest.address || "";
  document.getElementById("guestIdProof").value = guest.id_proof || "";
  document.getElementById("guestIdNumber").value = guest.id_number || "";

  const button = document.querySelector(".card button");
  button.textContent = "Update Guest";
  button.onclick = function () {
    const updatedData = {
      first_name: document.getElementById("guestFName").value,
      last_name: document.getElementById("guestLName").value,
      phone: document.getElementById("guestPhone").value,
      email: document.getElementById("guestEmail").value,
      address: document.getElementById("guestAddress").value,
      id_proof: document.getElementById("guestIdProof").value,
      id_number: document.getElementById("guestIdNumber").value
    };
    fetch(`/guests/${guest_id}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(updatedData)
    }).then(res => res.json()).then(result => {
      if (result.success) {
        alert("Guest updated successfully!");
        button.textContent = "Add Guest";
        button.onclick = addGuest;
        loadGuests();
      } else {
        alert("Failed to update guest.");
      }
    });
  };
}

function deleteGuest(guest_id) {
  if (!confirm("Are you sure you want to delete this guest?")) return;
  fetch(`/guests/${guest_id}`, { method: "DELETE" })
    .then(res => res.json())
    .then(result => {
      if (result.success) {
        alert("Guest deleted successfully!");
        loadGuests();
      } else {
        alert("Failed to delete guest.");
      }
    });
}

function loadRooms() {
  const bedType = document.getElementById("bedTypeSelect").value;
  fetch(`/rooms/by-type/${bedType}`)
    .then(res => res.json())
    .then(data => {
      const grid = document.getElementById("roomGrid");
      grid.innerHTML = "";

      for (let i = 0; i < 25; i++) {
        const room = data[i];
        const div = document.createElement("div");
        div.className = "room-box";
        div.textContent = room?.room_number || `R${i + 1}`;

        if (room && room.status.toLowerCase() === "available") {
          div.classList.add("available");
          div.onclick = () => {
            const guestId = document.getElementById("guestSelect").value;
            const checkIn = document.getElementById("checkIn").value;
            const checkOut = document.getElementById("checkOut").value;
            if (!guestId || !checkIn || !checkOut) return alert("Select guest and dates");
            assignRoom(room.room_id, guestId, checkIn, checkOut);
          };
        } else if (room) {
          div.classList.add("occupied");
          div.setAttribute("data-tooltip", room.guest_name || "Occupied");
          div.onclick = () => {
            if (confirm("Unassign this room?")) unassignRoom(room.room_id);
          };
        } else {
          div.style.background = "#bdc3c7";
        }

        grid.appendChild(div);
      }
    });
}

function assignRoom(roomId, guestId, checkIn, checkOut) {
  fetch("/assign-room", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ 
      room_id: parseInt(roomId, 10), 
      guest_id: parseInt(guestId, 10), 
      check_in: checkIn, 
      check_out: checkOut 
    })
  }).then(res => res.json()).then(data => {
    if (data.success) {
      loadRooms();
      loadRoomTable();
    } else {
      alert("Assignment failed: " + data.message);
    }
  });
}
function formatDateTime(dateStr) {
  const date = new Date(dateStr);
  return date.toLocaleString('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    hour12: false
  });
}


function unassignRoom(roomId) {
  fetch("/unassign-room", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ room_id: roomId })
  }).then(res => res.json()).then(data => {
    if (data.success) {
      loadRooms();
      loadRoomTable();
    } else {
      alert("Unassignment failed: " + data.message);
    }
  });
}

function loadRoomTable() {
  fetch("/rooms/all")
    .then(res => res.json())
    .then(data => {
      const tbody = document.getElementById("roomTableBody");
      tbody.innerHTML = "";
      if (data.length === 0) {
        tbody.innerHTML = "<tr><td colspan='6'>No room data available</td></tr>";
        return;
      }
      data.forEach(room => {
        const tr = document.createElement("tr");
        tr.innerHTML = `
          <td>${room.room_number}</td>
          <td>${room.room_type}</td>
          <td>${room.status}</td>
          <td>${room.guest_name || '-'}</td>
          <td>${room.check_in ? formatDateTime(room.check_in) : '-'}</td>
<td>${room.check_out ? formatDateTime(room.check_out) : '-'}</td>
        `;
        tbody.appendChild(tr);
      });
    });
}

window.onload = function () {
  const user = JSON.parse(localStorage.getItem('user'));
  if (!user) {
    window.location.href = "/";
    return;
  }
  showSection('guests');
  loadGuests();
  loadGuestDropdown();
  loadRooms();
  loadRoomTable();
};
