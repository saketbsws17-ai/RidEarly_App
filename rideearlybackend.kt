package com.pulseride.android

import android.Manifest
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.PatternItem
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.maps.android.SphericalUtil
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.pulseride.android.data.OpenRouteServiceRepository
import com.pulseride.android.databinding.ActivityMainBinding
import com.pulseride.android.databinding.ItemDriverOptionBinding
import com.pulseride.android.databinding.ItemEarningBinding
import com.pulseride.android.databinding.ItemFleetDriverBinding
import com.pulseride.android.databinding.ItemHistoryBinding
import com.pulseride.android.model.ActiveRide
import com.pulseride.android.model.Driver
import com.pulseride.android.model.DriverRecommendation
import com.pulseride.android.model.MapPlacementMode
import com.pulseride.android.model.PendingDriverDraft
import com.pulseride.android.model.RideRecord
import com.pulseride.android.model.RideStage
import com.pulseride.android.model.RouteInfo
import com.pulseride.android.model.RouteSource
import com.pulseride.android.model.VehicleType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private val routeRepository by lazy { OpenRouteServiceRepository(BuildConfig.ORS_API_KEY) }
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<MaterialCardView>

    private var googleMap: GoogleMap? = null
    private var pickupMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private val driverMarkers = mutableMapOf<Int, Marker>()
    private var accuracyCircle: Circle? = null
    private var approachPolyline: Polyline? = null
    private var tripPolyline: Polyline? = null

    private val drivers = mutableListOf<Driver>()
    private val history = mutableListOf<RideRecord>()
    private var recommendations: List<DriverRecommendation> = emptyList()
    private var tripPreview: RouteInfo? = null
    private var activeRide: ActiveRide? = null
    private var pendingDriverDraft: PendingDriverDraft? = null

    private var selectedVehicleType = VehicleType.MINI
    private var selectedDriverId: Int? = null
    private var selectedRating = 5
    private var pickupSource = "Not set"
    private var destinationSource = "Not set"
    private var placementMode = MapPlacementMode.NONE
    private var nextDriverId = 1
    private var routeLoading = false
    private var routeRequestVersion = 0

    private var refreshJob: Job? = null
    private var activeRideJob: Job? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (granted) {
                enableMyLocationLayer()
                fetchLivePickupLocation()
            } else {
                showToast("Location permission was not granted.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomSheet()
        setupMap()
        setupFormDefaults()
        setupVehicleFilter()
        setupInteractions()
        renderAll()
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        activeRideJob?.cancel()
        super.onDestroy()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        runCatching {
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark_mode))
            map.isTrafficEnabled = true
        }
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true
        map.uiSettings.isMapToolbarEnabled = false
        map.setOnMapClickListener(::handleMapTap)
        map.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDrag(marker: Marker) = Unit

            override fun onMarkerDragEnd(marker: Marker) {
                if (activeRide != null) {
                    return
                }

                when (val tag = marker.tag) {
                    PICKUP_TAG -> setPickup(marker.position, "Dragged pickup", null)
                    DESTINATION_TAG -> setDestination(marker.position, "Dragged destination")
                    is Int -> {
                        val driver = drivers.firstOrNull { it.id == tag } ?: return
                        driver.location = marker.position
                        renderFleet()
                        renderEarnings()
                        recomputeMatches()
                        showToast("${driver.name} moved to a new location.")
                    }
                }
            }
        })

        map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, 12.5f))
        binding.root.doOnLayout {
            updateMapPadding()
        }
        enableMyLocationLayer()
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun setupFormDefaults() {
        binding.inputDriverRating.setText("4.8")
        binding.inputDriverPrice.setText("14")
        binding.inputDriverType.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                VehicleType.entries.map { it.label }
            )
        )
        binding.inputDriverType.setText(VehicleType.MINI.label, false)
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheetCard)
        bottomSheetBehavior.isFitToContents = false
        bottomSheetBehavior.halfExpandedRatio = 0.56f
        bottomSheetBehavior.peekHeight = dp(330)
        bottomSheetBehavior.isHideable = false
        bottomSheetBehavior.isDraggable = true
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        binding.root.doOnLayout {
            bottomSheetBehavior.expandedOffset = binding.topOverlay.bottom + dp(14)
            updateMapPadding()
        }
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                updateMapPadding()
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                updateMapPadding()
            }
        })
    }

    private fun setupVehicleFilter() {
        binding.vehicleFilterGroup.removeAllViews()
        VehicleType.entries.forEach { type ->
            val checkedState = intArrayOf(android.R.attr.state_checked)
            val defaultState = intArrayOf()
            val chipFill = ColorStateList(
                arrayOf(checkedState, defaultState),
                intArrayOf(
                    ContextCompat.getColor(this, R.color.pulse_mint),
                    ContextCompat.getColor(this, R.color.pulse_surface_alt)
                )
            )
            val chipText = ColorStateList(
                arrayOf(checkedState, defaultState),
                intArrayOf(
                    ContextCompat.getColor(this, R.color.pulse_ink),
                    ContextCompat.getColor(this, R.color.pulse_text)
                )
            )
            val chipStroke = ColorStateList(
                arrayOf(checkedState, defaultState),
                intArrayOf(
                    ContextCompat.getColor(this, R.color.pulse_mint),
                    ContextCompat.getColor(this, R.color.pulse_outline)
                )
            )
            val chip = Chip(this).apply {
                text = type.label
                isCheckable = true
                isChecked = type == selectedVehicleType
                chipBackgroundColor = chipFill
                chipStrokeColor = chipStroke
                chipStrokeWidth = dp(1).toFloat()
                setTextColor(chipText)
                setOnClickListener {
                    selectedVehicleType = type
                    selectedDriverId = null
                    setupVehicleFilter()
                    recomputeMatches()
                }
            }
            binding.vehicleFilterGroup.addView(chip)
        }
    }

    private fun setupInteractions() {
        binding.buttonLiveLocation.setOnClickListener { requestLiveLocation() }
        binding.buttonPlacePickup.setOnClickListener { activatePlacementMode(MapPlacementMode.PICKUP) }
        binding.buttonPlaceDestination.setOnClickListener { activatePlacementMode(MapPlacementMode.DESTINATION) }
        binding.buttonFitMarkers.setOnClickListener { fitMapToScene() }
        binding.buttonAddDriver.setOnClickListener { queueDriverPlacement() }
        binding.buttonClearDriver.setOnClickListener { clearDriverForm() }
        binding.buttonRequestRide.setOnClickListener { requestRide() }
    }

    private fun activatePlacementMode(mode: MapPlacementMode) {
        if (activeRide != null) {
            showToast("Map placement is locked while a trip is active.")
            return
        }

        placementMode = mode
        pendingDriverDraft = if (mode == MapPlacementMode.DRIVER) pendingDriverDraft else null
        renderMapGuide()
        collapseSheetForMap()
        when (mode) {
            MapPlacementMode.PICKUP -> showToast("Tap the map to place the pickup.")
            MapPlacementMode.DESTINATION -> showToast("Tap the map to place the destination.")
            MapPlacementMode.DRIVER -> showToast("Tap the map to place the driver.")
            MapPlacementMode.NONE -> Unit
        }
    }

    private fun queueDriverPlacement() {
        if (activeRide != null) {
            showToast("Wait for the active trip to finish before adding a new driver.")
            return
        }

        val name = binding.inputDriverName.text?.toString()?.trim().orEmpty()
        val rating = binding.inputDriverRating.text?.toString()?.toDoubleOrNull()
        val pricePerKm = binding.inputDriverPrice.text?.toString()?.toDoubleOrNull()
        val vehicle = VehicleType.fromLabel(binding.inputDriverType.text?.toString().orEmpty())

        when {
            name.isBlank() -> {
                showToast("Enter the driver name first.")
                return
            }

            rating == null || rating !in 1.0..5.0 -> {
                showToast("Driver rating must be between 1 and 5.")
                return
            }

            pricePerKm == null || pricePerKm <= 0.0 -> {
                showToast("Price per km must be greater than zero.")
                return
            }
        }

        pendingDriverDraft = PendingDriverDraft(
            name = name,
            rating = rating,
            pricePerKm = pricePerKm,
            vehicleType = vehicle,
            available = binding.checkDriverAvailable.isChecked
        )
        placementMode = MapPlacementMode.DRIVER
        renderMapGuide()
        collapseSheetForMap()
        showToast("Tap the map to place $name.")
    }

    private fun clearDriverForm() {
        binding.inputDriverName.setText("")
        binding.inputDriverRating.setText("4.8")
        binding.inputDriverPrice.setText("14")
        binding.inputDriverType.setText(VehicleType.MINI.label, false)
        binding.checkDriverAvailable.isChecked = true

        if (placementMode == MapPlacementMode.DRIVER && activeRide == null) {
            placementMode = MapPlacementMode.NONE
            pendingDriverDraft = null
            renderMapGuide()
        }
    }

    private fun handleMapTap(latLng: LatLng) {
        if (activeRide != null) {
            showToast("The map is locked while a trip is active.")
            return
        }

        when (placementMode) {
            MapPlacementMode.PICKUP -> {
                setPickup(latLng, "Map pin", null)
                placementMode = MapPlacementMode.NONE
                renderMapGuide()
            }

            MapPlacementMode.DESTINATION -> {
                setDestination(latLng, "Map pin")
                placementMode = MapPlacementMode.NONE
                renderMapGuide()
            }

            MapPlacementMode.DRIVER -> {
                addDriverAt(latLng)
                placementMode = MapPlacementMode.NONE
                pendingDriverDraft = null
                renderMapGuide()
            }

            MapPlacementMode.NONE -> showToast("Choose pickup, destination, or add driver before tapping the map.")
        }
    }

    private fun addDriverAt(latLng: LatLng) {
        val draft = pendingDriverDraft ?: return
        val driver = Driver(
            id = nextDriverId++,
            name = draft.name,
            rating = draft.rating,
            pricePerKm = draft.pricePerKm,
            vehicleType = draft.vehicleType,
            available = draft.available,
            earnings = 0.0,
            location = latLng
        )

        drivers += driver
        attachDriverMarker(driver)
        clearDriverForm()
        renderAll()
        recomputeMatches()
        fitMapToScene()
        showToast("${driver.name} was added to the fleet.")
    }

    private fun attachDriverMarker(driver: Driver) {
        val map = googleMap ?: return
        val marker = map.addMarker(
            MarkerOptions()
                .position(driver.location)
                .title(driver.name)
                .draggable(true)
                .icon(BitmapDescriptorFactory.defaultMarker(driverMarkerHue(driver)))
        ) ?: return

        marker.tag = driver.id
        driverMarkers[driver.id] = marker
    }

    private fun setPickup(latLng: LatLng, source: String, accuracyMeters: Float?) {
        pickupSource = source
        val map = googleMap ?: return

        if (pickupMarker == null) {
            pickupMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Pickup")
                    .draggable(true)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            )
            pickupMarker?.tag = PICKUP_TAG
        } else {
            pickupMarker?.position = latLng
        }

        accuracyCircle?.remove()
        accuracyCircle = accuracyMeters?.let {
            map.addCircle(
                com.google.android.gms.maps.model.CircleOptions()
                    .center(latLng)
                    .radius(it.toDouble())
                    .strokeColor(ContextCompat.getColor(this, R.color.pulse_orange))
                    .fillColor(Color.argb(45, 255, 133, 82))
                    .strokeWidth(2f)
            )
        }

        renderLocationSummary()
        recomputeMatches()
    }

    private fun setDestination(latLng: LatLng, source: String) {
        destinationSource = source
        val map = googleMap ?: return

        if (destinationMarker == null) {
            destinationMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Destination")
                    .draggable(true)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
            )
            destinationMarker?.tag = DESTINATION_TAG
        } else {
            destinationMarker?.position = latLng
        }

        renderLocationSummary()
        recomputeMatches()
    }

    private fun requestLiveLocation() {
        if (hasLocationPermission()) {
            enableMyLocationLayer()
            fetchLivePickupLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun fetchLivePickupLocation() {
        if (activeRide != null) {
            showToast("Live pickup updates are locked while a ride is active.")
            return
        }

        val token = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { location: Location? ->
                if (location == null) {
                    showToast("Could not retrieve the current device location.")
                    return@addOnSuccessListener
                }

                val point = LatLng(location.latitude, location.longitude)
                setPickup(point, "Live device", location.accuracy)
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 15f))
                showToast("Pickup updated from live location.")
            }
            .addOnFailureListener {
                showToast("Could not access the current device location.")
            }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun enableMyLocationLayer() {
        val map = googleMap ?: return
        if (hasLocationPermission()) {
            runCatching {
                map.isMyLocationEnabled = true
            }
        }
    }

    private fun recomputeMatches() {
        refreshJob?.cancel()
        val requestId = ++routeRequestVersion

        if (activeRide != null) {
            routeLoading = false
            renderAll()
            updateRoutePolylines()
            return
        }

        val pickup = pickupMarker?.position
        val destination = destinationMarker?.position

        if (pickup == null || destination == null) {
            routeLoading = false
            tripPreview = null
            recommendations = emptyList()
            selectedDriverId = null
            renderAll()
            updateRoutePolylines()
            return
        }

        routeLoading = true
        renderRouteSummary()
        renderRecommendations()

        refreshJob = lifecycleScope.launch {
            val trip = routeRepository.getRoute(pickup, destination)

            if (!isActive || requestId != routeRequestVersion) {
                return@launch
            }

            tripPreview = trip

            val candidates = drivers.filter { it.available && it.vehicleType == selectedVehicleType }
            if (candidates.isEmpty()) {
                recommendations = emptyList()
                selectedDriverId = null
                routeLoading = false
                renderAll()
                updateRoutePolylines()
                return@launch
            }

            val surge = surgeMultiplier()
            val ranked = mutableListOf<DriverRecommendation>()

            for (driver in candidates) {
                val approach = routeRepository.getRoute(driver.location, pickup)
                if (!isActive || requestId != routeRequestVersion) {
                    return@launch
                }

                val tripDistanceKm = trip.distanceMeters / 1000.0
                val approachDistanceKm = approach.distanceMeters / 1000.0
                val fare = (tripDistanceKm * driver.pricePerKm * surge) + minOf(60.0, approachDistanceKm * 3.0)
                val score = (fare * 0.5) - (driver.rating * 2.0) + (approachDistanceKm * 0.3)

                ranked += DriverRecommendation(
                    driverId = driver.id,
                    fare = fare,
                    score = score,
                    approachRoute = approach,
                    tripRoute = trip
                )
            }

            if (!isActive || requestId != routeRequestVersion) {
                return@launch
            }

            recommendations = ranked.sortedBy { it.score }.take(3)
            if (recommendations.none { it.driverId == selectedDriverId }) {
                selectedDriverId = recommendations.firstOrNull()?.driverId
            }
            routeLoading = false
            renderAll()
            updateRoutePolylines()
        }
    }

    private fun requestRide() {
        if (activeRide != null) {
            showToast("Finish the current ride before starting another.")
            return
        }

        val recommendation = recommendations.firstOrNull { it.driverId == selectedDriverId }
        val driver = drivers.firstOrNull { it.id == selectedDriverId }
        val pickup = pickupMarker?.position
        val destination = destinationMarker?.position

        if (recommendation == null || driver == null || pickup == null || destination == null) {
            showToast("Pickup, destination, and a selected driver are all required.")
            return
        }

        driver.available = false
        activeRide = ActiveRide(
            driverId = driver.id,
            riderName = binding.inputRiderName.text?.toString()?.trim().orEmpty().ifBlank { "Guest rider" },
            riderPhone = binding.inputRiderPhone.text?.toString()?.trim().orEmpty().ifBlank { "Not provided" },
            pickup = pickup,
            destination = destination,
            fare = recommendation.fare,
            approachRoute = recommendation.approachRoute,
            tripRoute = recommendation.tripRoute,
            stage = RideStage.DRIVER_TO_PICKUP,
            liveEtaSeconds = recommendation.approachRoute.durationSeconds
        )
        selectedRating = 5

        renderAll()
        updateRoutePolylines()
        fitMapToScene()
        showToast("${driver.name} accepted the trip.")

        activeRideJob?.cancel()
        activeRideJob = lifecycleScope.launch {
            animateDriver(driver, recommendation.approachRoute, RideStage.DRIVER_TO_PICKUP)
            val ride = activeRide ?: return@launch

            ride.stage = RideStage.TRIP_TO_DESTINATION
            ride.liveEtaSeconds = ride.tripRoute.durationSeconds
            renderAll()
            updateRoutePolylines()
            showToast("${driver.name} reached pickup and started the trip.")

            animateDriver(driver, ride.tripRoute, RideStage.TRIP_TO_DESTINATION)
            if (activeRide == null) {
                return@launch
            }

            activeRide?.stage = RideStage.COMPLETED_WAITING_RATING
            activeRide?.liveEtaSeconds = 0
            renderAll()
            updateRoutePolylines()
            showToast("Trip finished. Submit a rating to close the ride.")
        }
    }

    private suspend fun animateDriver(driver: Driver, route: RouteInfo, stage: RideStage) {
        val marker = driverMarkers[driver.id] ?: return
        val path = if (route.points.size >= 2) route.points else listOf(marker.position, marker.position)
        val durationMs = when (stage) {
            RideStage.DRIVER_TO_PICKUP -> (route.durationSeconds * 120L).coerceIn(4_000L, 16_000L)
            RideStage.TRIP_TO_DESTINATION -> (route.durationSeconds * 100L).coerceIn(6_000L, 22_000L)
            RideStage.COMPLETED_WAITING_RATING -> 1_000L
        }

        val startedAt = SystemClock.elapsedRealtime()

        while (coroutineContext.isActive) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val progress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            val nextPoint = interpolatePolyline(path, progress)
            marker.position = nextPoint
            driver.location = nextPoint

            activeRide?.takeIf { it.driverId == driver.id && it.stage == stage }?.let { ride ->
                ride.liveEtaSeconds = ((1f - progress) * route.durationSeconds).roundToInt().coerceAtLeast(0)
                renderTripStatus()
            }

            if (progress >= 1f) {
                break
            }
            delay(80L)
        }

        if (path.isNotEmpty()) {
            val lastPoint = path.last()
            marker.position = lastPoint
            driver.location = lastPoint
        }
    }

    private fun completeRide(rating: Int) {
        val ride = activeRide ?: return
        val driver = drivers.firstOrNull { it.id == ride.driverId } ?: return

        driver.rating = (driver.rating + rating) / 2.0
        driver.earnings += ride.fare
        driver.available = true
        driver.location = ride.destination

        history.add(
            0,
            RideRecord(
                riderName = ride.riderName,
                driverName = driver.name,
                vehicleType = driver.vehicleType,
                fare = ride.fare,
                rating = rating,
                distanceMeters = ride.tripRoute.distanceMeters
            )
        )

        pickupMarker?.position = ride.destination
        pickupSource = "Last drop-off"
        destinationMarker?.remove()
        destinationMarker = null
        destinationSource = "Not set"
        accuracyCircle?.remove()
        accuracyCircle = null

        activeRide = null
        tripPreview = null
        recommendations = emptyList()
        selectedDriverId = null

        renderAll()
        updateRoutePolylines()
        recomputeMatches()
        showToast("${driver.name} is back online and the ride has been logged.")
    }

    private fun renderAll() {
        renderHeroStats()
        renderMapGuide()
        renderLocationSummary()
        renderFleet()
        renderRecommendations()
        renderTripStatus()
        renderHistory()
        renderEarnings()
        updateDriverMarkers()
        updateMapPadding()
    }

    private fun renderHeroStats() {
        binding.textAvailableCount.text = drivers.count { it.available }.toString()
        binding.textRouteMetric.text = tripPreview?.let { formatDistance(it.distanceMeters) } ?: "--"
        binding.textSurgeMultiplier.text = String.format("%.1fx", surgeMultiplier())
    }

    private fun renderMapGuide() {
        val (headline, body) = when {
            activeRide?.stage == RideStage.DRIVER_TO_PICKUP ->
                "Driver is heading to pickup" to "Markers are locked while the booked driver follows the selected route."

            activeRide?.stage == RideStage.TRIP_TO_DESTINATION ->
                "Trip is live on the map" to "The driver marker is now following the rider's trip route to the destination."

            activeRide?.stage == RideStage.COMPLETED_WAITING_RATING ->
                "Trip is complete" to "Submit the rating below to finish the ride and unlock the map."

            placementMode == MapPlacementMode.PICKUP ->
                "Place the pickup point" to "Tap the map anywhere to drop the pickup marker."

            placementMode == MapPlacementMode.DESTINATION ->
                "Place the destination point" to "Tap the map anywhere to drop the destination marker."

            placementMode == MapPlacementMode.DRIVER ->
                "Place the driver" to "Tap the map to place the pending driver from the form."

            else ->
                "Ready to place trip data" to "Use the buttons below to place pickup, destination, or drivers on the map."
        }

        binding.textMapMode.text = headline
        binding.textMapGuide.text = body

        stylePlacementButton(binding.buttonPlacePickup, placementMode == MapPlacementMode.PICKUP)
        stylePlacementButton(binding.buttonPlaceDestination, placementMode == MapPlacementMode.DESTINATION)
        binding.buttonAddDriver.alpha = if (placementMode == MapPlacementMode.DRIVER) 1f else 0.92f
    }

    private fun renderLocationSummary() {
        binding.textPickupSummary.text = pickupMarker?.position?.let {
            "$pickupSource\n${formatLatLng(it)}"
        } ?: "Not set"

        binding.textDestinationSummary.text = destinationMarker?.position?.let {
            "$destinationSource\n${formatLatLng(it)}"
        } ?: "Not set"

        renderRouteSummary()
    }

    private fun renderRouteSummary() {
        binding.textRouteSummary.text = when {
            routeLoading -> "Calculating a live road route..."
            tripPreview == null -> "Set pickup and destination to calculate a route."
            else -> {
                val sourceLabel = when (tripPreview?.source) {
                    RouteSource.ORS -> "HeiGIT ORS route"
                    RouteSource.FALLBACK -> "Fallback line"
                    RouteSource.SAME_POINT -> "Same point"
                    null -> "Route"
                }
                "${formatDistance(tripPreview!!.distanceMeters)} | ${formatDuration(tripPreview!!.durationSeconds)} | $sourceLabel"
            }
        }
    }

    private fun renderFleet() {
        binding.fleetContainer.removeAllViews()
        if (drivers.isEmpty()) {
            binding.fleetContainer.addView(buildPlaceholderView("No drivers added yet."))
            return
        }

        val inflater = LayoutInflater.from(this)
        drivers.forEach { driver ->
            val item = ItemFleetDriverBinding.inflate(inflater, binding.fleetContainer, false)
            item.textFleetName.text = driver.name
            item.textFleetMeta.text = "${driver.vehicleType.label} | Rs ${driver.pricePerKm.format(1)}/km | ${driver.rating.format(1)} star | ${formatLatLng(driver.location)}"
            item.textFleetStatus.text = if (driver.available) "Available" else "Busy"
            item.textFleetStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (driver.available) R.color.pulse_mint else R.color.pulse_busy
                )
            )

            item.buttonFocusDriver.setOnClickListener {
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(driver.location, 15f))
            }
            item.buttonToggleDriver.setText(if (driver.available) "Pause" else "Enable")
            item.buttonToggleDriver.setOnClickListener {
                if (activeRide?.driverId == driver.id) {
                    showToast("The active trip driver cannot be paused right now.")
                    return@setOnClickListener
                }
                driver.available = !driver.available
                renderAll()
                recomputeMatches()
            }
            item.buttonRemoveDriver.setOnClickListener {
                if (activeRide?.driverId == driver.id) {
                    showToast("The active trip driver cannot be removed.")
                    return@setOnClickListener
                }
                driverMarkers.remove(driver.id)?.remove()
                drivers.removeAll { it.id == driver.id }
                renderAll()
                recomputeMatches()
            }
            binding.fleetContainer.addView(item.root)
        }
    }

    private fun renderRecommendations() {
        binding.recommendationsContainer.removeAllViews()

        if (activeRide != null) {
            binding.buttonRequestRide.isEnabled = false
            val activeDriver = drivers.firstOrNull { it.id == activeRide?.driverId }
            binding.textSelectedDriver.text = activeDriver?.let { "${it.name} is booked" } ?: "Driver is booked"
            binding.recommendationsContainer.addView(
                buildPlaceholderView(activeRideStatusText())
            )
            return
        }

        if (pickupMarker == null || destinationMarker == null) {
            binding.buttonRequestRide.isEnabled = false
            binding.textSelectedDriver.text = "Add pickup and destination first"
            binding.recommendationsContainer.addView(
                buildPlaceholderView("Pickup and destination are both required before matching starts.")
            )
            return
        }

        if (routeLoading) {
            binding.buttonRequestRide.isEnabled = false
            binding.textSelectedDriver.text = "Finding nearby drivers..."
            binding.recommendationsContainer.addView(
                buildPlaceholderView("Calculating routes and scoring the fleet...")
            )
            return
        }

        if (recommendations.isEmpty()) {
            binding.buttonRequestRide.isEnabled = false
            binding.textSelectedDriver.text = "No ${selectedVehicleType.label} drivers available"
            binding.recommendationsContainer.addView(
                buildPlaceholderView("Add a matching driver or switch the vehicle filter.")
            )
            return
        }

        val inflater = LayoutInflater.from(this)
        recommendations.forEachIndexed { index, recommendation ->
            val driver = drivers.firstOrNull { it.id == recommendation.driverId } ?: return@forEachIndexed
            val item = ItemDriverOptionBinding.inflate(inflater, binding.recommendationsContainer, false)
            item.textDriverName.text = "${index + 1}. ${driver.name}"
            item.textDriverMeta.text = "${driver.vehicleType.label} | score ${recommendation.score.format(1)}"
            item.textDriverEta.text = formatDuration(recommendation.approachRoute.durationSeconds)
            item.textDriverStats.text =
                "Fare Rs ${recommendation.fare.format(0)} | ${formatDistance(recommendation.approachRoute.distanceMeters)} away | ${formatDistance(recommendation.tripRoute.distanceMeters)} trip | ${driver.rating.format(1)} star"

            val selected = selectedDriverId == recommendation.driverId
            item.cardRecommendation.strokeWidth = if (selected) 4 else 0
            item.cardRecommendation.strokeColor = ContextCompat.getColor(this, R.color.pulse_mint)
            item.cardRecommendation.setOnClickListener {
                selectedDriverId = recommendation.driverId
                renderRecommendations()
                updateRoutePolylines()
                updateDriverMarkers()
            }
            binding.recommendationsContainer.addView(item.root)
        }

        val selectedRecommendation = recommendations.firstOrNull { it.driverId == selectedDriverId }
        binding.buttonRequestRide.isEnabled = selectedRecommendation != null
        binding.textSelectedDriver.text = selectedRecommendation?.let {
            val driver = drivers.firstOrNull { item -> item.id == it.driverId }
            "${driver?.name.orEmpty()} | Rs ${it.fare.format(0)} | pickup in ${formatDuration(it.approachRoute.durationSeconds)}"
        } ?: "Pick a recommendation"
    }

    private fun renderTripStatus() {
        binding.tripActionsContainer.removeAllViews()

        val ride = activeRide
        if (ride == null) {
            binding.textTripHeadline.text = "Your live booking status will appear here."
            binding.textTripBody.text = "Set pickup, destination, and at least one driver to begin."
            return
        }

        val driver = drivers.firstOrNull { it.id == ride.driverId }
        when (ride.stage) {
            RideStage.DRIVER_TO_PICKUP -> {
                binding.textTripHeadline.text = "${driver?.name.orEmpty()} is heading to pickup"
                binding.textTripBody.text =
                    "Fare Rs ${ride.fare.format(0)} | Pickup ETA ${formatDuration(ride.liveEtaSeconds)} | Rider ${ride.riderName}"
            }

            RideStage.TRIP_TO_DESTINATION -> {
                binding.textTripHeadline.text = "${driver?.name.orEmpty()} is driving to the destination"
                binding.textTripBody.text =
                    "${formatDistance(ride.tripRoute.distanceMeters)} trip | ETA ${formatDuration(ride.liveEtaSeconds)} | Phone ${ride.riderPhone}"
            }

            RideStage.COMPLETED_WAITING_RATING -> {
                binding.textTripHeadline.text = "Trip complete. Submit the rider rating."
                binding.textTripBody.text =
                    "${driver?.name.orEmpty()} reached the destination. Select a rating and finish the ride."
                renderRatingActions()
            }
        }
    }

    private fun renderRatingActions() {
        val ratingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        (1..5).forEach { value ->
            val button = MaterialButton(this).apply {
                text = value.toString()
                setBackgroundColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (value == selectedRating) R.color.pulse_gold else R.color.pulse_surface_alt
                    )
                )
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.pulse_text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 8
                }
                setOnClickListener {
                    selectedRating = value
                    renderTripStatus()
                }
            }
            ratingRow.addView(button)
        }

        val confirmButton = MaterialButton(this).apply {
            text = "Submit rating and finish"
            setOnClickListener { completeRide(selectedRating) }
        }

        binding.tripActionsContainer.addView(ratingRow)
        binding.tripActionsContainer.addView(confirmButton)
    }

    private fun renderHistory() {
        binding.historyContainer.removeAllViews()
        if (history.isEmpty()) {
            binding.historyContainer.addView(buildPlaceholderView("No rides completed yet."))
            return
        }

        val inflater = LayoutInflater.from(this)
        history.take(5).forEach { ride ->
            val item = ItemHistoryBinding.inflate(inflater, binding.historyContainer, false)
            item.textHistoryTitle.text = "${ride.riderName} with ${ride.driverName}"
            item.textHistoryMeta.text =
                "${ride.vehicleType.label} | Rs ${ride.fare.format(0)} | ${formatDistance(ride.distanceMeters)} | rated ${ride.rating}/5"
            binding.historyContainer.addView(item.root)
        }
    }

    private fun renderEarnings() {
        binding.earningsContainer.removeAllViews()
        if (drivers.isEmpty()) {
            binding.earningsContainer.addView(buildPlaceholderView("Add a driver to start tracking earnings."))
            return
        }

        val inflater = LayoutInflater.from(this)
        drivers.sortedByDescending { it.earnings }.take(5).forEachIndexed { index, driver ->
            val item = ItemEarningBinding.inflate(inflater, binding.earningsContainer, false)
            item.textEarningTitle.text = "${index + 1}. ${driver.name}"
            item.textEarningMeta.text = "${driver.vehicleType.label} | ${driver.rating.format(1)} star"
            item.textEarningAmount.text = "Rs ${driver.earnings.format(0)}"
            binding.earningsContainer.addView(item.root)
        }
    }

    private fun updateDriverMarkers() {
        drivers.forEach { driver ->
            val marker = driverMarkers[driver.id]
            marker?.position = driver.location
            marker?.setIcon(BitmapDescriptorFactory.defaultMarker(driverMarkerHue(driver)))
            marker?.isDraggable = activeRide == null
        }

        pickupMarker?.isDraggable = activeRide == null
        destinationMarker?.isDraggable = activeRide == null
    }

    private fun updateRoutePolylines() {
        approachPolyline?.remove()
        approachPolyline = null
        tripPolyline?.remove()
        tripPolyline = null

        val map = googleMap ?: return
        val ride = activeRide
        if (ride != null) {
            approachPolyline = map.addPolyline(
                buildPolyline(
                    ride.approachRoute,
                    ContextCompat.getColor(this, R.color.pulse_mint),
                    if (ride.stage == RideStage.DRIVER_TO_PICKUP) null else DASH_PATTERN
                )
            )

            tripPolyline = map.addPolyline(
                buildPolyline(
                    ride.tripRoute,
                    if (ride.stage == RideStage.TRIP_TO_DESTINATION) {
                        ContextCompat.getColor(this, R.color.pulse_orange)
                    } else {
                        ContextCompat.getColor(this, R.color.pulse_gold)
                    },
                    if (ride.stage == RideStage.TRIP_TO_DESTINATION) null else DASH_PATTERN
                )
            )
            return
        }

        tripPreview?.let {
            tripPolyline = map.addPolyline(
                buildPolyline(
                    it,
                    ContextCompat.getColor(this, R.color.pulse_gold),
                    DASH_PATTERN
                )
            )
        }

        recommendations.firstOrNull { it.driverId == selectedDriverId }?.let {
            approachPolyline = map.addPolyline(
                buildPolyline(
                    it.approachRoute,
                    ContextCompat.getColor(this, R.color.pulse_mint),
                    DASH_PATTERN
                )
            )
        }
    }

    private fun buildPolyline(route: RouteInfo, color: Int, pattern: List<PatternItem>?): PolylineOptions {
        return PolylineOptions()
            .addAll(route.points)
            .color(color)
            .width(12f)
            .pattern(pattern)
    }

    private fun fitMapToScene() {
        val map = googleMap ?: return
        val points = mutableListOf<LatLng>()
        pickupMarker?.position?.let(points::add)
        destinationMarker?.position?.let(points::add)
        driverMarkers.values.mapTo(points) { it.position }

        runCatching {
            when {
                points.isEmpty() -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, 12.5f))
                points.size == 1 || points.maxDistanceMeters() < 40.0 ->
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 15.5f))

                else -> {
                    val builder = LatLngBounds.Builder()
                    points.forEach(builder::include)
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), dp(96)))
                }
            }
        }.onFailure {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(points.firstOrNull() ?: DEFAULT_CENTER, 14f))
        }
    }

    private fun updateMapPadding() {
        val map = googleMap ?: return
        if (binding.root.height == 0 || binding.topOverlay.height == 0) {
            binding.root.doOnLayout { updateMapPadding() }
            return
        }

        val visibleSheetHeight = (binding.root.height - binding.bottomSheetCard.top).coerceAtLeast(0)
        val bottomInset = min(
            max(visibleSheetHeight, bottomSheetBehavior.peekHeight),
            dp(360)
        )
        map.setPadding(
            dp(18),
            binding.topOverlay.height + dp(26),
            dp(18),
            bottomInset + dp(18)
        )
    }

    private fun collapseSheetForMap() {
        if (::bottomSheetBehavior.isInitialized) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        updateMapPadding()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun stylePlacementButton(button: MaterialButton, active: Boolean) {
        val fillColor = ContextCompat.getColor(
            this,
            if (active) R.color.pulse_mint else R.color.pulse_surface
        )
        val strokeColor = ContextCompat.getColor(
            this,
            if (active) R.color.pulse_mint else R.color.pulse_outline
        )
        val textColor = ContextCompat.getColor(
            this,
            if (active) R.color.pulse_ink else R.color.pulse_text
        )

        button.alpha = if (active) 1f else 0.9f
        button.strokeWidth = dp(if (active) 2 else 1)
        button.backgroundTintList = ColorStateList.valueOf(fillColor)
        button.strokeColor = ColorStateList.valueOf(strokeColor)
        button.setTextColor(textColor)
    }

    private fun List<LatLng>.maxDistanceMeters(): Double {
        var maxDistance = 0.0
        for (start in indices) {
            for (end in start + 1 until size) {
                maxDistance = max(maxDistance, SphericalUtil.computeDistanceBetween(this[start], this[end]))
            }
        }
        return maxDistance
    }

    private fun buildPlaceholderView(message: String): View {
        return TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.pulse_muted))
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun activeRideStatusText(): String {
        val ride = activeRide ?: return ""
        return when (ride.stage) {
            RideStage.DRIVER_TO_PICKUP -> "Driver is heading to pickup."
            RideStage.TRIP_TO_DESTINATION -> "Trip is moving to the destination."
            RideStage.COMPLETED_WAITING_RATING -> "Trip is complete. Waiting for rating."
        }
    }

    private fun surgeMultiplier(): Double {
        val availableCount = drivers.count { it.available }
        return when {
            availableCount <= 2 -> 1.5
            availableCount <= 4 -> 1.2
            else -> 1.0
        }
    }

    private fun driverMarkerHue(driver: Driver): Float {
        return when {
            activeRide?.driverId == driver.id -> BitmapDescriptorFactory.HUE_AZURE
            selectedDriverId == driver.id -> BitmapDescriptorFactory.HUE_CYAN
            driver.available -> BitmapDescriptorFactory.HUE_GREEN
            else -> BitmapDescriptorFactory.HUE_ROSE
        }
    }

    private fun interpolatePolyline(points: List<LatLng>, progress: Float): LatLng {
        if (points.size == 1) {
            return points.first()
        }

        val lengths = mutableListOf<Double>()
        var total = 0.0
        for (index in 0 until points.lastIndex) {
            val segment = SphericalUtil.computeDistanceBetween(points[index], points[index + 1])
            lengths += segment
            total += segment
        }

        var target = total * progress
        for (index in lengths.indices) {
            val segmentLength = lengths[index]
            if (target <= segmentLength) {
                val start = points[index]
                val end = points[index + 1]
                val ratio = if (segmentLength == 0.0) 0.0 else target / segmentLength
                return LatLng(
                    start.latitude + ((end.latitude - start.latitude) * ratio),
                    start.longitude + ((end.longitude - start.longitude) * ratio)
                )
            }
            target -= segmentLength
        }

        return points.last()
    }

    private fun formatLatLng(point: LatLng): String {
        return "${point.latitude.format(5)}, ${point.longitude.format(5)}"
    }

    private fun formatDistance(distanceMeters: Int): String {
        return String.format("%.1f km", distanceMeters / 1000.0)
    }

    private fun formatDuration(durationSeconds: Int): String {
        val minutes = max(1, (durationSeconds / 60.0).roundToInt())
        return "$minutes min"
    }

    private fun Double.format(scale: Int): String {
        return "%.${scale}f".format(this)
    }

    private companion object {
        val DEFAULT_CENTER = LatLng(12.9716, 77.5946)
        const val PICKUP_TAG = "pickup"
        const val DESTINATION_TAG = "destination"
        val DASH_PATTERN = listOf(Dash(28f), Gap(20f))
    }
}
