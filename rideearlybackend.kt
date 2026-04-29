package com.pulseride.android

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PatternItem
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.maps.android.SphericalUtil
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
import com.pulseride.android.model.RideTier
import com.pulseride.android.model.RouteInfo
import com.pulseride.android.model.RouteSource
import com.pulseride.android.model.VehicleType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<MaterialCardView>
    private lateinit var placesClient: PlacesClient

    private val routeRepository by lazy { OpenRouteServiceRepository(BuildConfig.ORS_API_KEY) }
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

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

    private var currentScreen = AppScreen.RIDER
    private var currentStep = BookingStep.ROUTE
    private var selectedVehicleType = VehicleType.MINI
    private var searchTarget = SearchTarget.PICKUP
    private var selectedDriverId: Int? = null
    private var selectedRating = 5
    private var pickupSource = "Not set"
    private var destinationSource = "Not set"
    private var placementMode = MapPlacementMode.NONE
    private var fleetPanelExpanded = true
    private var searchSessionToken: AutocompleteSessionToken? = null
    private var placeSearchRequestVersion = 0
    private var searchPredictions: List<AutocompletePrediction> = emptyList()

    private var nextDriverId = 1
    private var routeLoading = false
    private var routeRequestVersion = 0
    private var lastExpandedOffset = -1
    private var lastPeekHeight = -1
    private var lastMapTopPadding = -1
    private var lastMapBottomPadding = -1
    private var isSheetCollapsed = true
    private var hasResolvedInitialMapLocation = false
    private var refreshJob: Job? = null
    private var activeRideJob: Job? = null
    private var placeSearchJob: Job? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                enableMyLocationLayer()
                fetchLivePickupLocation(showFeedback = false)
            } else {
                showFallbackMapLocation()
                showToast("Location permission was not granted.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets()

        setupBottomSheet()
        setupMap()
        setupPlaces()
        setupFormDefaults()
        setupVehicleFilter()
        setupInteractions()
        renderAll()
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        activeRideJob?.cancel()
        placeSearchJob?.cancel()
        super.onDestroy()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topOverlay.updateLayoutParams<androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams> {
                topMargin = systemBars.top + dp(16)
            }
            binding.sheetScrollView.updatePadding(bottom = systemBars.bottom + dp(32))
            updateMapPadding()
            insets
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        runCatching {
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark_mode))
            map.isTrafficEnabled = true
        }
        map.uiSettings.apply {
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMyLocationButtonEnabled = true
            isMapToolbarEnabled = false
        }
        map.setOnMapClickListener(::handleMapTap)
        map.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDrag(marker: Marker) = Unit

            override fun onMarkerDragEnd(marker: Marker) {
                if (activeRide != null) return
                when (val tag = marker.tag) {
                    PICKUP_TAG -> setPickup(marker.position, "Dragged pickup", null)
                    DESTINATION_TAG -> setDestination(marker.position, "Dragged destination")
                    is Int -> {
                        drivers.firstOrNull { it.id == tag }?.let { driver ->
                            driver.location = marker.position
                            renderFleet()
                            renderEarnings()
                            recomputeMatches()
                            showToast("${driver.name} moved.")
                        }
                    }
                }
            }
        })

        binding.root.doOnLayout { updateMapPadding() }
        resolveInitialMapLocation()
    }

    private fun setupMap() {
        (supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment)
            .getMapAsync(this)
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheetCard).apply {
            isFitToContents = true
            peekHeight = dp(96)
            isHideable = false
            isDraggable = true
            state = BottomSheetBehavior.STATE_COLLAPSED
        }

        binding.root.doOnLayout {
            refreshSheetChrome()
        }
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> isSheetCollapsed = false
                    BottomSheetBehavior.STATE_COLLAPSED -> isSheetCollapsed = true
                    else -> return
                }
                renderSheetState()
                updateMapPadding()
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })
    }

    private fun setupPlaces() {
        val key = BuildConfig.PLACES_API_KEY
        if (key.isBlank() || key.contains("DEFAULT", ignoreCase = true)) {
            binding.inputPlaceSearch.isEnabled = false
            binding.inputPlaceSearch.hint = "Enable Places API to search"
            return
        }

        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(applicationContext, key)
        }
        placesClient = Places.createClient(this)
        binding.inputPlaceSearch.isEnabled = true
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

    private fun setupVehicleFilter() {
        binding.vehicleFilterGroup.removeAllViews()
        VehicleType.entries.forEach { type ->
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            binding.vehicleFilterGroup.addView(
                Chip(this).apply {
                    text = type.label
                    isCheckable = true
                    isChecked = type == selectedVehicleType
                    chipStrokeWidth = dp(1).toFloat()
                    chipBackgroundColor = ColorStateList(
                        states,
                        intArrayOf(
                            ContextCompat.getColor(context, R.color.pulse_orange),
                            ContextCompat.getColor(context, R.color.pulse_surface_alt)
                        )
                    )
                    chipStrokeColor = ColorStateList(
                        states,
                        intArrayOf(
                            ContextCompat.getColor(context, R.color.pulse_orange),
                            ContextCompat.getColor(context, R.color.pulse_outline)
                        )
                    )
                    setTextColor(
                        ColorStateList(
                            states,
                            intArrayOf(
                                ContextCompat.getColor(context, R.color.pulse_ink),
                                ContextCompat.getColor(context, R.color.pulse_text)
                            )
                        )
                    )
                    setOnClickListener {
                        selectedVehicleType = type
                        selectedDriverId = null
                        currentStep = BookingStep.SELECT
                        setupVehicleFilter()
                        recomputeMatches()
                    }
                }
            )
        }
    }

    private fun setupInteractions() {
        binding.buttonScreenRider.setOnClickListener { switchScreen(AppScreen.RIDER) }
        binding.buttonScreenDriver.setOnClickListener { switchScreen(AppScreen.DRIVER) }
        binding.buttonSearchPickupTarget.setOnClickListener {
            searchTarget = SearchTarget.PICKUP
            renderSearchChrome()
            focusSearch()
        }
        binding.buttonSearchDestinationTarget.setOnClickListener {
            searchTarget = SearchTarget.DESTINATION
            renderSearchChrome()
            focusSearch()
        }
        binding.buttonClearPlaceSearch.setOnClickListener { clearPlaceSearch() }
        binding.inputPlaceSearch.doAfterTextChanged { editable ->
            val query = editable?.toString()?.trim().orEmpty()
            handleSearchQueryChanged(query)
        }
        binding.buttonLiveLocation.setOnClickListener { requestLiveLocation() }
        binding.buttonFitMarkers.setOnClickListener { fitMapToScene() }
        binding.buttonPlacePickup.setOnClickListener { activatePlacementMode(MapPlacementMode.PICKUP) }
        binding.buttonPlaceDestination.setOnClickListener { activatePlacementMode(MapPlacementMode.DESTINATION) }
        binding.buttonContinueJourney.setOnClickListener { previewRideOptions() }
        binding.buttonBackToJourney.setOnClickListener {
            showStep(BookingStep.ROUTE, expand = true)
            fitMapToScene()
        }
        binding.buttonRequestRide.setOnClickListener { requestRide() }
        binding.buttonDriverFocusMap.setOnClickListener { fitDriverFleet() }
        binding.buttonToggleFleetPanel.setOnClickListener { toggleFleetPanel() }
        binding.buttonAddDriver.setOnClickListener { queueDriverPlacement() }
        binding.buttonClearDriver.setOnClickListener { clearDriverForm() }
    }

    private fun focusSearch() {
        binding.inputPlaceSearch.requestFocus()
        binding.inputPlaceSearch.setSelection(binding.inputPlaceSearch.text?.length ?: 0)
    }

    private fun handleSearchQueryChanged(query: String) {
        renderSearchChrome()
        placeSearchJob?.cancel()

        if (query.isBlank()) {
            searchSessionToken = null
            searchPredictions = emptyList()
            renderPlaceSuggestions()
            return
        }

        if (!::placesClient.isInitialized) {
            return
        }

        if (searchSessionToken == null) {
            searchSessionToken = AutocompleteSessionToken.newInstance()
        }

        val requestId = ++placeSearchRequestVersion
        placeSearchJob = lifecycleScope.launch {
            delay(250L)
            requestPlacePredictions(query, requestId)
        }
    }

    private fun requestPlacePredictions(query: String, requestId: Int) {
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setLocationBias(currentSearchBounds())
            .setSessionToken(searchSessionToken)
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                if (requestId != placeSearchRequestVersion) return@addOnSuccessListener
                if (binding.inputPlaceSearch.text?.toString()?.trim() != query) return@addOnSuccessListener
                searchPredictions = response.autocompletePredictions.take(5)
                renderPlaceSuggestions()
            }
            .addOnFailureListener {
                if (requestId != placeSearchRequestVersion) return@addOnFailureListener
                searchPredictions = emptyList()
                renderPlaceSuggestions("Search is temporarily unavailable.")
            }
    }

    private fun currentSearchBounds(): RectangularBounds {
        googleMap?.projection?.visibleRegion?.latLngBounds?.let { visibleBounds ->
            return RectangularBounds.newInstance(visibleBounds)
        }

        val delta = 0.18
        return RectangularBounds.newInstance(
            LatLng(DEFAULT_CENTER.latitude - delta, DEFAULT_CENTER.longitude - delta),
            LatLng(DEFAULT_CENTER.latitude + delta, DEFAULT_CENTER.longitude + delta)
        )
    }

    private fun renderPlaceSuggestions(overrideMessage: String? = null) {
        if (currentScreen != AppScreen.RIDER) {
            binding.placeSuggestionsContainer.removeAllViews()
            binding.placeSuggestionsContainer.isVisible = false
            return
        }

        binding.placeSuggestionsContainer.removeAllViews()
        val query = binding.inputPlaceSearch.text?.toString()?.trim().orEmpty()
        val shouldShow = query.isNotBlank() || overrideMessage != null
        binding.placeSuggestionsContainer.isVisible = shouldShow
        if (!shouldShow) return

        when {
            overrideMessage != null -> {
                val placeholder = buildSuggestionPlaceholder(overrideMessage)
                binding.placeSuggestionsContainer.addView(placeholder)
                animateEntry(placeholder)
            }

            searchPredictions.isEmpty() -> {
                val placeholder = buildSuggestionPlaceholder("No matching places found in this map area.")
                binding.placeSuggestionsContainer.addView(placeholder)
                animateEntry(placeholder)
            }

            else -> {
                searchPredictions.forEachIndexed { index, prediction ->
                    val row = buildSuggestionRow(prediction)
                    binding.placeSuggestionsContainer.addView(row)
                    animateEntry(row, index)
                }
            }
        }
    }

    private fun buildSuggestionPlaceholder(message: String): View {
        return MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(context, R.color.pulse_outline)
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.pulse_surface))
            addView(
                TextView(context).apply {
                    text = message
                    setTextColor(ContextCompat.getColor(context, R.color.pulse_muted))
                    textSize = 13f
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                }
            )
        }
    }

    private fun buildSuggestionRow(prediction: AutocompletePrediction): View {
        val primary = prediction.getPrimaryText(null).toString()
        val secondary = prediction.getSecondaryText(null).toString()
        return MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(context, R.color.pulse_outline)
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.pulse_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    addView(
                        TextView(context).apply {
                            text = primary
                            setTextColor(ContextCompat.getColor(context, R.color.pulse_text))
                            textSize = 15f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        }
                    )
                    if (secondary.isNotBlank()) {
                        addView(
                            TextView(context).apply {
                                text = secondary
                                setTextColor(ContextCompat.getColor(context, R.color.pulse_muted))
                                textSize = 12f
                                setPadding(0, dp(4), 0, 0)
                            }
                        )
                    }
                }
            )
            setOnClickListener { fetchPlaceForPrediction(prediction) }
        }
    }

    private fun fetchPlaceForPrediction(prediction: AutocompletePrediction) {
        if (activeRide != null) {
            showToast("Search is locked while the trip is active.")
            return
        }
        if (!::placesClient.isInitialized) {
            showToast("Places search is not available right now.")
            return
        }

        val request = FetchPlaceRequest.builder(
            prediction.placeId,
            listOf(
                Place.Field.DISPLAY_NAME,
                Place.Field.FORMATTED_ADDRESS,
                Place.Field.LOCATION
            )
        ).apply {
            setSessionToken(searchSessionToken)
        }.build()

        placesClient.fetchPlace(request)
            .addOnSuccessListener { response ->
                applyFetchedPlace(response.place)
            }
            .addOnFailureListener {
                showToast("Could not open that place.")
            }
    }

    private fun applyFetchedPlace(place: Place) {
        val location = place.location ?: return
        val title = place.displayName?.ifBlank { null }
        val subtitle = place.formattedAddress?.ifBlank { null }
        val source = listOfNotNull(title, subtitle).joinToString("\n").ifBlank {
            "Searched place"
        }

        when (searchTarget) {
            SearchTarget.PICKUP -> {
                setPickup(location, source, null)
                if (destinationMarker == null) {
                    searchTarget = SearchTarget.DESTINATION
                }
            }

            SearchTarget.DESTINATION -> setDestination(location, source)
        }

        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
        clearPlaceSearch()
        renderSearchChrome()
        fitMapToScene()
    }

    private fun clearPlaceSearch() {
        placeSearchJob?.cancel()
        binding.inputPlaceSearch.text?.clear()
        searchSessionToken = null
        searchPredictions = emptyList()
        renderPlaceSuggestions()
        renderSearchChrome()
    }

    private fun previewRideOptions() {
        when {
            pickupMarker == null -> showToast("Place the pickup first.")
            destinationMarker == null -> showToast("Place the destination first.")
            routeLoading -> showToast("Route is still calculating.")
            tripPreview == null -> showToast("Route preview is not ready yet.")
            else -> {
                currentScreen = AppScreen.RIDER
                showStep(BookingStep.SELECT, expand = true)
            }
        }
    }

    private fun toggleFleetPanel() {
        fleetPanelExpanded = !fleetPanelExpanded
        renderFlow()
        updateMapPadding()
    }

    private fun activatePlacementMode(mode: MapPlacementMode) {
        if (activeRide != null) {
            showToast("Map placement is locked while a trip is active.")
            return
        }

        currentScreen = if (mode == MapPlacementMode.DRIVER) AppScreen.DRIVER else AppScreen.RIDER
        placementMode = mode
        if (mode != MapPlacementMode.DRIVER) {
            pendingDriverDraft = null
        }
        currentStep = BookingStep.ROUTE
        renderMapGuide()
        collapseSheetForMap()

        when (mode) {
            MapPlacementMode.PICKUP -> showToast("Tap the map to place pickup.")
            MapPlacementMode.DESTINATION -> showToast("Tap the map to place destination.")
            MapPlacementMode.DRIVER -> showToast("Tap the map to place the driver.")
            MapPlacementMode.NONE -> Unit
        }
    }

    private fun queueDriverPlacement() {
        if (activeRide != null) {
            showToast("Wait for the active trip to finish before adding a driver.")
            return
        }

        val name = binding.inputDriverName.text?.toString()?.trim().orEmpty()
        val rating = binding.inputDriverRating.text?.toString()?.toDoubleOrNull()
        val pricePerKm = binding.inputDriverPrice.text?.toString()?.toDoubleOrNull()
        val vehicleType = VehicleType.fromLabel(binding.inputDriverType.text?.toString().orEmpty())

        when {
            name.isBlank() -> showToast("Enter a driver name first.")
            rating == null || rating !in 1.0..5.0 -> showToast("Driver rating must be between 1 and 5.")
            pricePerKm == null || pricePerKm <= 0.0 -> showToast("Price per km must be greater than zero.")
            else -> {
                pendingDriverDraft = PendingDriverDraft(
                    name = name,
                    rating = rating,
                    pricePerKm = pricePerKm,
                    vehicleType = vehicleType,
                    available = binding.checkDriverAvailable.isChecked
                )
                currentScreen = AppScreen.DRIVER
                placementMode = MapPlacementMode.DRIVER
                currentStep = BookingStep.ROUTE
                renderMapGuide()
                collapseSheetForMap()
                showToast("Tap the map to place $name.")
            }
        }
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
            showToast("Map is locked while the trip is active.")
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

            MapPlacementMode.NONE -> showToast("Choose pickup, destination, or add driver first.")
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
        showToast("${driver.name} added to fleet.")
    }

    private fun attachDriverMarker(driver: Driver) {
        googleMap?.addMarker(
            MarkerOptions()
                .position(driver.location)
                .title(driver.name)
                .draggable(true)
                .icon(BitmapDescriptorFactory.defaultMarker(driverMarkerHue(driver)))
        )?.let { marker ->
            marker.tag = driver.id
            driverMarkers[driver.id] = marker
        }
    }

    private fun setPickup(latLng: LatLng, source: String, accuracyMeters: Float?) {
        currentScreen = AppScreen.RIDER
        currentStep = BookingStep.ROUTE
        pickupSource = source
        pickupMarker = pickupMarker?.apply {
            position = latLng
        } ?: googleMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Pickup")
                .draggable(true)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )?.apply {
            tag = PICKUP_TAG
        }

        accuracyCircle?.remove()
        accuracyCircle = accuracyMeters?.let {
            googleMap?.addCircle(
                CircleOptions()
                    .center(latLng)
                    .radius(it.toDouble())
                    .strokeColor(ContextCompat.getColor(this, R.color.pulse_orange))
                    .fillColor(Color.argb(38, 3, 200, 20))
                    .strokeWidth(2f)
            )
        }

        if (destinationMarker == null && searchTarget == SearchTarget.PICKUP) {
            searchTarget = SearchTarget.DESTINATION
        }
        renderSearchChrome()
        renderLocationSummary()
        recomputeMatches()
    }

    private fun setDestination(latLng: LatLng, source: String) {
        currentScreen = AppScreen.RIDER
        currentStep = BookingStep.ROUTE
        destinationSource = source
        destinationMarker = destinationMarker?.apply {
            position = latLng
        } ?: googleMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Destination")
                .draggable(true)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
        )?.apply {
            tag = DESTINATION_TAG
        }

        renderSearchChrome()
        renderLocationSummary()
        recomputeMatches()
    }

    private fun requestLiveLocation() {
        currentScreen = AppScreen.RIDER
        if (hasLocationPermission()) {
            enableMyLocationLayer()
            fetchLivePickupLocation(showFeedback = true)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun fetchLivePickupLocation(showFeedback: Boolean) {
        if (activeRide != null) {
            showToast("Live location is locked while the trip is active.")
            return
        }

        fusedLocationClient
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location: Location? ->
                location?.let {
                    hasResolvedInitialMapLocation = true
                    val point = LatLng(it.latitude, it.longitude)
                    setPickup(point, "Live device", it.accuracy)
                    googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 15f))
                    if (showFeedback) {
                        showToast("Pickup updated from live location.")
                    }
                } ?: run {
                    showFallbackMapLocation()
                    if (showFeedback) {
                        showToast("Could not retrieve location.")
                    }
                }
            }
            .addOnFailureListener {
                showFallbackMapLocation()
                if (showFeedback) {
                    showToast("Could not access device location.")
                }
            }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun enableMyLocationLayer() {
        if (hasLocationPermission()) {
            runCatching { googleMap?.isMyLocationEnabled = true }
        }
    }

    private fun resolveInitialMapLocation() {
        if (hasResolvedInitialMapLocation) return
        if (hasLocationPermission()) {
            enableMyLocationLayer()
            fetchLivePickupLocation(showFeedback = false)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun showFallbackMapLocation() {
        if (hasResolvedInitialMapLocation) return
        hasResolvedInitialMapLocation = true
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, 12.5f))
    }

    private fun recomputeMatches() {
        refreshJob?.cancel()
        val requestId = ++routeRequestVersion

        if (activeRide != null || pickupMarker?.position == null || destinationMarker?.position == null) {
            routeLoading = false
            tripPreview = null
            recommendations = emptyList()
            selectedDriverId = null
            renderAll()
            updateRoutePolylines()
            return
        }

        val pickup = pickupMarker!!.position
        val destination = destinationMarker!!.position
        routeLoading = true
        renderRouteSummary()
        renderRecommendations()

        refreshJob = lifecycleScope.launch {
            val tripRoute = routeRepository.getRoute(pickup, destination)
            if (!isActive || requestId != routeRequestVersion) return@launch

            tripPreview = tripRoute
            val availableDrivers = drivers.filter {
                it.available && it.vehicleType == selectedVehicleType
            }

            if (availableDrivers.isEmpty()) {
                recommendations = emptyList()
                selectedDriverId = null
                routeLoading = false
                renderAll()
                updateRoutePolylines()
                return@launch
            }

            val rankedRecommendations = mutableListOf<DriverRecommendation>()
            for (driver in availableDrivers) {
                val approachRoute = routeRepository.getRoute(driver.location, pickup)
                if (!isActive || requestId != routeRequestVersion) return@launch

                val fare = calculateFare(driver, tripRoute, approachRoute)
                val score = calculateScore(driver, fare, approachRoute)
                rankedRecommendations += DriverRecommendation(
                    driverId = driver.id,
                    tier = driver.vehicleType.toRideTier(),
                    fare = fare,
                    score = score,
                    approachRoute = approachRoute,
                    tripRoute = tripRoute
                )
            }

            if (!isActive || requestId != routeRequestVersion) return@launch

            recommendations = rankedRecommendations.sortedBy { it.score }.take(3)
            if (recommendations.none { it.driverId == selectedDriverId }) {
                selectedDriverId = recommendations.firstOrNull()?.driverId
            }
            routeLoading = false
            renderAll()
            updateRoutePolylines()
        }
    }

    private fun calculateFare(driver: Driver, tripRoute: RouteInfo, approachRoute: RouteInfo): Double {
        val tripFare = (tripRoute.distanceMeters / 1000.0) * driver.pricePerKm * surgeMultiplier()
        val approachFee = minOf(60.0, (approachRoute.distanceMeters / 1000.0) * 3.0)
        return tripFare + approachFee
    }

    private fun calculateScore(driver: Driver, fare: Double, approachRoute: RouteInfo): Double {
        val proximityPenalty = approachRoute.durationSeconds / 60.0
        val ratingBoost = driver.rating * 3.0
        return (fare * 0.45) + proximityPenalty - ratingBoost
    }

    private fun requestRide() {
        if (activeRide != null) {
            showToast("Finish the current ride first.")
            return
        }

        val recommendation = recommendations.firstOrNull { it.driverId == selectedDriverId }
        val driver = drivers.firstOrNull { it.id == selectedDriverId }
        val pickup = pickupMarker?.position
        val destination = destinationMarker?.position

        if (recommendation == null || driver == null || pickup == null || destination == null) {
            showToast("Ride request data is incomplete.")
            return
        }

        driver.available = false
        selectedRating = 5
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

        currentScreen = AppScreen.RIDER
        showStep(BookingStep.TRACK, expand = true)
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
            showToast("${driver.name} started the trip.")

            animateDriver(driver, ride.tripRoute, RideStage.TRIP_TO_DESTINATION)
            activeRide?.let { finishedRide ->
                finishedRide.stage = RideStage.COMPLETED_WAITING_RATING
                finishedRide.liveEtaSeconds = 0
                currentScreen = AppScreen.RIDER
                showStep(BookingStep.TRACK, expand = true)
                updateRoutePolylines()
                showToast("Trip finished. Rate the driver to close the trip.")
            }
        }
    }

    private suspend fun animateDriver(driver: Driver, route: RouteInfo, stage: RideStage) {
        val marker = driverMarkers[driver.id] ?: return
        val path = if (route.points.size >= 2) {
            route.points
        } else {
            listOf(marker.position, marker.position)
        }

        val durationMs = when (stage) {
            RideStage.DRIVER_TO_PICKUP -> (route.durationSeconds * 120L).coerceIn(4_000L, 16_000L)
            RideStage.TRIP_TO_DESTINATION -> (route.durationSeconds * 100L).coerceIn(6_000L, 22_000L)
            RideStage.COMPLETED_WAITING_RATING -> 1_000L
        }

        val startedAt = SystemClock.elapsedRealtime()
        while (coroutineContext.isActive) {
            val progress = (
                (SystemClock.elapsedRealtime() - startedAt).toFloat() / durationMs.toFloat()
                ).coerceIn(0f, 1f)

            val position = interpolatePolyline(path, progress)
            marker.position = position
            driver.location = position

            activeRide
                ?.takeIf { it.driverId == driver.id && it.stage == stage }
                ?.let { ride ->
                    ride.liveEtaSeconds = ((1f - progress) * route.durationSeconds)
                        .roundToInt()
                        .coerceAtLeast(0)
                    renderTripStatus()
                }

            if (progress >= 1f) break
            delay(80L)
        }

        path.lastOrNull()?.let {
            marker.position = it
            driver.location = it
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
        searchTarget = SearchTarget.DESTINATION
        currentScreen = AppScreen.RIDER
        showStep(BookingStep.ROUTE, expand = false)
        renderAll()
        updateRoutePolylines()
        recomputeMatches()
        showToast("${driver.name} is back online.")
    }

    private fun switchScreen(screen: AppScreen, expand: Boolean = true) {
        currentScreen = screen
        if (::bottomSheetBehavior.isInitialized && expand) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            isSheetCollapsed = false
        }
        renderAll()
        if (expand) {
            binding.sheetScrollView.post { binding.sheetScrollView.smoothScrollTo(0, 0) }
        }
    }

    private fun showStep(step: BookingStep, expand: Boolean) {
        currentStep = step
        if (::bottomSheetBehavior.isInitialized) {
            bottomSheetBehavior.state = if (expand) {
                BottomSheetBehavior.STATE_EXPANDED
            } else {
                BottomSheetBehavior.STATE_COLLAPSED
            }
            isSheetCollapsed = !expand
        }
        renderAll()
        if (expand) {
            scrollSheetToStep(step)
        }
    }

    private fun renderAll() {
        renderSearchChrome()
        renderFlow()
        renderHeroStats()
        renderMapGuide()
        renderLocationSummary()
        renderDriverSummary()
        renderFleet()
        renderRecommendations()
        renderTripStatus()
        renderHistory()
        renderEarnings()
        updateDriverMarkers()
        renderSheetState()
        refreshSheetChrome()
        updateMapPadding()
    }

    private fun renderSearchChrome() {
        val query = binding.inputPlaceSearch.text?.toString()?.trim().orEmpty()
        binding.buttonClearPlaceSearch.isVisible = currentScreen == AppScreen.RIDER && query.isNotBlank()
        binding.inputPlaceSearch.hint = if (searchTarget == SearchTarget.PICKUP) {
            "Search pick-up places in the city"
        } else {
            "Search destination places in the city"
        }

        val enabled = currentScreen == AppScreen.RIDER && activeRide == null && ::placesClient.isInitialized
        binding.inputPlaceSearch.isEnabled = enabled
        binding.buttonSearchPickupTarget.isEnabled = currentScreen == AppScreen.RIDER && activeRide == null
        binding.buttonSearchDestinationTarget.isEnabled = currentScreen == AppScreen.RIDER && activeRide == null

        styleSearchTargetButton(
            binding.buttonSearchPickupTarget,
            searchTarget == SearchTarget.PICKUP
        )
        styleSearchTargetButton(
            binding.buttonSearchDestinationTarget,
            searchTarget == SearchTarget.DESTINATION
        )
    }

    private fun renderFlow() {
        val showingRider = currentScreen == AppScreen.RIDER
        binding.cardRiderOverlay.isVisible = showingRider
        binding.cardDriverOverlay.isVisible = !showingRider
        binding.pageRiderContent.isVisible = showingRider
        binding.pageDriverContent.isVisible = !showingRider
        binding.sectionJourney.isVisible = showingRider
        binding.sectionSelection.isVisible = showingRider
        binding.sectionTracking.isVisible =
            showingRider && activeRide != null
        binding.fleetPanelBody.isVisible = !showingRider && fleetPanelExpanded
        binding.buttonToggleFleetPanel.text =
            if (fleetPanelExpanded) "Hide tools" else "Show tools"

        styleScreenButton(binding.buttonScreenRider, showingRider)
        styleScreenButton(binding.buttonScreenDriver, !showingRider)

        when {
            !showingRider -> {
                binding.textHeroTitle.text = "Driver workspace"
                binding.textHeroBody.text =
                    "Add drivers, move them on the map, and manage availability without mixing rider booking details into the same page."
            }

            currentStep == BookingStep.ROUTE -> {
                binding.textHeroTitle.text = "Where do you want to go?"
                binding.textHeroBody.text =
                    "Add a pick-up and destination, then swipe through the best ride options on the map."
            }

            currentStep == BookingStep.SELECT -> {
                binding.textHeroTitle.text = "Pick your ${selectedVehicleType.label.lowercase()} ride"
                binding.textHeroBody.text =
                    "The best nearby ${selectedVehicleType.label.lowercase()} drivers are ranked by fare, rating, and arrival time."
            }

            else -> {
                binding.textHeroTitle.text = "Your driver is on the way"
                binding.textHeroBody.text =
                    "Follow the moving driver, ETA, and trip status while all the ride controls stay available below."
            }
        }
    }

    private fun renderSheetState() {
        val compact = !isSheetFullyExpanded()
        binding.cardCollapsedDock.isVisible = false
        binding.sheetScrollView.isVisible = !compact
        binding.sheetScrollView.isNestedScrollingEnabled = !compact
    }

    private fun renderHeroStats() {
        binding.textAvailableCount.text = drivers.count { it.available }.toString()
        binding.textRouteMetric.text = tripPreview?.let { formatDistance(it.distanceMeters) } ?: "--"
        binding.textSurgeMultiplier.text = String.format("%.1fx", surgeMultiplier())
    }

    private fun renderMapGuide() {
        val (modeLabel, guideText) = when {
            activeRide?.stage == RideStage.DRIVER_TO_PICKUP ->
                "LIVE TRACK" to "Driver is heading to pickup. The map is locked while the trip is active."

            activeRide?.stage == RideStage.TRIP_TO_DESTINATION ->
                "IN TRIP" to "Driver is following the route. Watch the live marker and ETA."

            activeRide?.stage == RideStage.COMPLETED_WAITING_RATING ->
                "TRIP DONE" to "Submit the rider rating below to unlock the next booking."

            placementMode == MapPlacementMode.PICKUP ->
                "PIN PICKUP" to "Tap anywhere on the map to place the pickup marker."

            placementMode == MapPlacementMode.DESTINATION ->
                "PIN DEST" to "Tap anywhere on the map to place the destination marker."

            placementMode == MapPlacementMode.DRIVER ->
                "PIN DRIVER" to "Tap anywhere on the map to place the pending driver."

            currentStep == BookingStep.SELECT ->
                "MATCH" to "Review the top ${selectedVehicleType.label.lowercase()} drivers below and tap a card to highlight that driver on the map."

            currentStep == BookingStep.TRACK ->
                "TRACK" to "The map stays focused on the live ride while the tracking card updates below."

            else ->
                "ROUTE" to "Drop both trip pins from the control sheet and fit the map when ready."
        }

        binding.textMapMode.text = modeLabel
        binding.textMapGuide.text = guideText
        binding.textDriverOverlayTitle.text = when {
            placementMode == MapPlacementMode.DRIVER && pendingDriverDraft != null ->
                "Place ${pendingDriverDraft?.name} on the map"

            activeRide != null -> "Fleet is serving an active ride"
            else -> "Driver workspace"
        }
        binding.textDriverOverlayBody.text = when {
            placementMode == MapPlacementMode.DRIVER && pendingDriverDraft != null ->
                "Tap anywhere on the map to place ${pendingDriverDraft?.name} and add that driver to the fleet."

            activeRide != null -> "You can still manage the rest of the fleet while the current trip is running."
            else -> "Use the driver page to add drivers, focus the fleet, and keep availability separate from rider booking."
        }
        binding.buttonDriverFocusMap.text = if (placementMode == MapPlacementMode.DRIVER) {
            "Waiting for map pin"
        } else {
            "Fit fleet"
        }

        stylePlacementButton(binding.buttonPlacePickup, placementMode == MapPlacementMode.PICKUP)
        stylePlacementButton(binding.buttonPlaceDestination, placementMode == MapPlacementMode.DESTINATION)
        binding.buttonAddDriver.alpha = if (placementMode == MapPlacementMode.DRIVER) 1f else 0.95f
    }

    private fun renderDriverSummary() {
        binding.textDriverFleetCount.text = drivers.size.toString()
        binding.textDriverAvailableCount.text = drivers.count { it.available }.toString()
        binding.textDriverRevenueTotal.text = "Rs ${drivers.sumOf { it.earnings }.format(0)}"
    }

    private fun refreshSheetChrome() {
        binding.topOverlay.post {
            if (::bottomSheetBehavior.isInitialized) {
                val targetPeekHeight = dp(96)

                if (lastPeekHeight != targetPeekHeight) {
                    bottomSheetBehavior.peekHeight = targetPeekHeight
                    lastPeekHeight = targetPeekHeight
                }

                updateMapPadding()
            }
        }
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
            routeLoading -> "Calculating the route and the best ${selectedVehicleType.label.lowercase()} driver matches..."
            tripPreview == null -> "Set pickup and destination to calculate the route."
            else -> {
                val route = tripPreview!!
                "${formatDistance(route.distanceMeters)} | ${formatDuration(route.durationSeconds)} | ${routeSourceLabel(route.source)}"
            }
        }
        binding.buttonContinueJourney.text = "Show ${selectedVehicleType.label} rides"
        binding.buttonContinueJourney.isEnabled =
            pickupMarker != null && destinationMarker != null && !routeLoading && tripPreview != null
    }

    private fun renderFleet() {
        binding.fleetContainer.removeAllViews()
        if (drivers.isEmpty()) {
            val placeholder = buildPlaceholderView("No drivers added yet.")
            binding.fleetContainer.addView(placeholder)
            animateEntry(placeholder)
            return
        }

        val inflater = LayoutInflater.from(this)
        drivers.forEachIndexed { index, driver ->
            val item = ItemFleetDriverBinding.inflate(inflater, binding.fleetContainer, false)
            item.textFleetName.text = driver.name
            item.textFleetMeta.text =
                "${driver.vehicleType.label} | Rs ${driver.pricePerKm.format(1)}/km | ${driver.rating.format(1)}* | ${formatLatLng(driver.location)}"
            item.textFleetStatus.text = if (driver.available) "Available" else "Busy"
            item.textFleetStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (driver.available) R.color.pulse_orange else R.color.pulse_busy
                )
            )
            item.buttonFocusDriver.setOnClickListener {
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(driver.location, 15f))
            }
            item.buttonToggleDriver.text = if (driver.available) "Pause" else "Enable"
            item.buttonToggleDriver.setOnClickListener {
                if (activeRide?.driverId == driver.id) {
                    showToast("Cannot pause the active driver.")
                } else {
                    driver.available = !driver.available
                    renderAll()
                    recomputeMatches()
                }
            }
            item.buttonRemoveDriver.setOnClickListener {
                if (activeRide?.driverId == driver.id) {
                    showToast("Cannot remove the active driver.")
                } else {
                    driverMarkers.remove(driver.id)?.remove()
                    drivers.removeAll { it.id == driver.id }
                    renderAll()
                    recomputeMatches()
                }
            }
            binding.fleetContainer.addView(item.root)
            animateEntry(item.root, index)
        }
    }

    private fun renderRecommendations() {
        binding.recommendationsContainer.removeAllViews()

        when {
            activeRide != null -> {
                binding.buttonRequestRide.isEnabled = false
                binding.textSelectedDriver.text = "Trip already in progress"
                val placeholder = buildPlaceholderView(activeRideStatusText())
                binding.recommendationsContainer.addView(placeholder)
                animateEntry(placeholder)
                return
            }

            pickupMarker == null || destinationMarker == null -> {
                binding.buttonRequestRide.isEnabled = false
                binding.textSelectedDriver.text = "Add route points first"
                val placeholder = buildPlaceholderView("Pickup and destination are required.")
                binding.recommendationsContainer.addView(placeholder)
                animateEntry(placeholder)
                return
            }

            routeLoading -> {
                binding.buttonRequestRide.isEnabled = false
                binding.textSelectedDriver.text = "Finding nearby drivers"
                val placeholder = buildPlaceholderView("Route and driver options are loading...")
                binding.recommendationsContainer.addView(placeholder)
                animateEntry(placeholder)
                return
            }

            recommendations.isEmpty() -> {
                binding.buttonRequestRide.isEnabled = false
                binding.textSelectedDriver.text = "No ${selectedVehicleType.label} drivers available"
                val placeholder = buildPlaceholderView(
                    "No available ${selectedVehicleType.label.lowercase()} drivers match the current route."
                )
                binding.recommendationsContainer.addView(placeholder)
                animateEntry(placeholder)
                return
            }
        }

        val inflater = LayoutInflater.from(this)
        recommendations.forEachIndexed { index, recommendation ->
            val driver = drivers.firstOrNull { it.id == recommendation.driverId } ?: return@forEachIndexed
            val item = ItemDriverOptionBinding.inflate(inflater, binding.recommendationsContainer, false)
            item.textDriverName.text = driver.name
            item.textDriverMeta.text =
                "${driver.vehicleType.label} | ${recommendation.tier.label} | ${driver.rating.format(1)}*"
            item.textDriverEta.text = formatDuration(recommendation.approachRoute.durationSeconds)
            item.textDriverStats.text =
                "Fare Rs ${recommendation.fare.format(0)} | ${formatDistance(recommendation.approachRoute.distanceMeters)} away | ${formatDistance(recommendation.tripRoute.distanceMeters)} trip"

            val isSelected = selectedDriverId == recommendation.driverId
            item.cardRecommendation.strokeWidth = dp(if (isSelected) 2 else 1)
            item.cardRecommendation.strokeColor = ContextCompat.getColor(
                this,
                if (isSelected) R.color.pulse_orange else R.color.pulse_outline
            )
            item.cardRecommendation.setOnClickListener {
                selectedDriverId = recommendation.driverId
                currentStep = BookingStep.SELECT
                renderRecommendations()
                updateRoutePolylines()
                updateDriverMarkers()
                fitMapToScene()
            }
            binding.recommendationsContainer.addView(item.root)
            animateEntry(item.root, index)
        }

        val selected = recommendations.firstOrNull { it.driverId == selectedDriverId }
        binding.buttonRequestRide.isEnabled = selected != null
        binding.textSelectedDriver.text = selected?.let { recommendation ->
            val driver = drivers.firstOrNull { it.id == recommendation.driverId }
            "${driver?.name.orEmpty()} | ${driver?.vehicleType?.label.orEmpty()} | Rs ${recommendation.fare.format(0)} | ${formatDuration(recommendation.approachRoute.durationSeconds)}"
        } ?: "Choose a ride to continue"
    }

    private fun renderTripStatus() {
        binding.tripActionsContainer.removeAllViews()
        val ride = activeRide
        if (ride == null) {
            binding.textTripHeadline.text = "No active trip"
            binding.textTripBody.text = "Book a driver to start live tracking and trip updates."
            binding.textTrackingDriverMeta.text = "Driver details"
            binding.textTrackingPickup.text = "Pickup: Not set"
            binding.textTrackingDestination.text = "Destination: Not set"
            return
        }

        val driver = drivers.firstOrNull { it.id == ride.driverId }
        binding.textTrackingDriverMeta.text =
            "${driver?.name.orEmpty()} | ${driver?.vehicleType?.label.orEmpty()} | ${driver?.rating?.format(1).orEmpty()}*"
        binding.textTrackingPickup.text = "Pickup: ${formatLatLng(ride.pickup)}"
        binding.textTrackingDestination.text = "Destination: ${formatLatLng(ride.destination)}"

        when (ride.stage) {
            RideStage.DRIVER_TO_PICKUP -> {
                binding.textTripHeadline.text = "${driver?.name.orEmpty()} is heading to pickup"
                binding.textTripBody.text =
                    "Fare Rs ${ride.fare.format(0)} | ETA ${formatDuration(ride.liveEtaSeconds)} | Rider ${ride.riderName}"
            }

            RideStage.TRIP_TO_DESTINATION -> {
                binding.textTripHeadline.text = "${driver?.name.orEmpty()} is driving to destination"
                binding.textTripBody.text =
                    "${formatDistance(ride.tripRoute.distanceMeters)} | ETA ${formatDuration(ride.liveEtaSeconds)} | ${ride.riderPhone}"
            }

            RideStage.COMPLETED_WAITING_RATING -> {
                binding.textTripHeadline.text = "Trip complete"
                binding.textTripBody.text = "Submit the rider rating to close this trip and unlock the next booking."
                renderRatingActions()
            }
        }
    }

    private fun renderRatingActions() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            weightSum = 5f
        }

        (1..5).forEach { rating ->
            row.addView(
                MaterialButton(this).apply {
                    text = rating.toString()
                    backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(
                            context,
                            if (rating == selectedRating) R.color.pulse_orange else R.color.pulse_surface_alt
                        )
                    )
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (rating == selectedRating) R.color.pulse_ink else R.color.pulse_text
                        )
                    )
                    strokeWidth = dp(1)
                    strokeColor = ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.pulse_outline)
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (rating < 5) {
                            marginEnd = dp(8)
                        }
                    }
                    setOnClickListener {
                        selectedRating = rating
                        renderTripStatus()
                    }
                }
            )
        }
        binding.tripActionsContainer.addView(row)

        binding.tripActionsContainer.addView(
            MaterialButton(this).apply {
                text = "Submit rating"
                backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.pulse_orange)
                )
                setTextColor(ContextCompat.getColor(context, R.color.pulse_ink))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                }
                setOnClickListener { completeRide(selectedRating) }
            }
        )
    }

    private fun renderHistory() {
        binding.historyContainer.removeAllViews()
        if (history.isEmpty()) {
            val placeholder = buildPlaceholderView("No completed rides yet.")
            binding.historyContainer.addView(placeholder)
            animateEntry(placeholder)
            return
        }

        history.take(5).forEachIndexed { index, ride ->
            val item = ItemHistoryBinding.inflate(LayoutInflater.from(this), binding.historyContainer, false)
            item.textHistoryTitle.text = "${ride.riderName} with ${ride.driverName}"
            item.textHistoryMeta.text =
                "${ride.vehicleType.label} | Rs ${ride.fare.format(0)} | ${formatDistance(ride.distanceMeters)} | ${ride.rating}/5"
            binding.historyContainer.addView(item.root)
            animateEntry(item.root, index)
        }
    }

    private fun renderEarnings() {
        binding.earningsContainer.removeAllViews()
        if (drivers.isEmpty()) {
            val placeholder = buildPlaceholderView("No drivers in the sandbox yet.")
            binding.earningsContainer.addView(placeholder)
            animateEntry(placeholder)
            return
        }

        drivers
            .sortedByDescending { it.earnings }
            .take(5)
            .forEachIndexed { index, driver ->
                val item = ItemEarningBinding.inflate(LayoutInflater.from(this), binding.earningsContainer, false)
                item.textEarningTitle.text = "${index + 1}. ${driver.name}"
                item.textEarningMeta.text = "${driver.vehicleType.label} | ${driver.rating.format(1)}*"
                item.textEarningAmount.text = "Rs ${driver.earnings.format(0)}"
                binding.earningsContainer.addView(item.root)
                animateEntry(item.root, index)
            }
    }

    private fun updateDriverMarkers() {
        drivers.forEach { driver ->
            driverMarkers[driver.id]?.apply {
                position = driver.location
                setIcon(BitmapDescriptorFactory.defaultMarker(driverMarkerHue(driver)))
                isDraggable = activeRide == null
            }
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
                    route = ride.approachRoute,
                    color = ContextCompat.getColor(this, R.color.pulse_orange),
                    pattern = if (ride.stage == RideStage.DRIVER_TO_PICKUP) null else DASH_PATTERN
                )
            )
            tripPolyline = map.addPolyline(
                buildPolyline(
                    route = ride.tripRoute,
                    color = ContextCompat.getColor(
                        this,
                        if (ride.stage == RideStage.TRIP_TO_DESTINATION) R.color.pulse_gold else R.color.pulse_outline
                    ),
                    pattern = if (ride.stage == RideStage.TRIP_TO_DESTINATION) null else DASH_PATTERN
                )
            )
            return
        }

        tripPreview?.let { route ->
            tripPolyline = map.addPolyline(
                buildPolyline(
                    route = route,
                    color = ContextCompat.getColor(this, R.color.pulse_gold),
                    pattern = DASH_PATTERN
                )
            )
        }
        recommendations.firstOrNull { it.driverId == selectedDriverId }?.let { selected ->
            approachPolyline = map.addPolyline(
                buildPolyline(
                    route = selected.approachRoute,
                    color = ContextCompat.getColor(this, R.color.pulse_orange),
                    pattern = DASH_PATTERN
                )
            )
        }
    }

    private fun buildPolyline(
        route: RouteInfo,
        color: Int,
        pattern: List<PatternItem>?
    ): PolylineOptions {
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

        val focusDriverId = activeRide?.driverId ?: selectedDriverId
        if (focusDriverId != null) {
            driverMarkers[focusDriverId]?.position?.let(points::add)
        } else {
            driverMarkers.values.forEach { marker -> points += marker.position }
        }

        runCatching {
            when {
                points.isEmpty() -> map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, 12.5f)
                )

                points.size == 1 || points.maxDistanceMeters() < 40.0 -> map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(points.first(), 15.5f)
                )

                else -> {
                    val bounds = LatLngBounds.Builder()
                    points.forEach(bounds::include)
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(bounds.build(), dp(92))
                    )
                }
            }
        }.onFailure {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(points.firstOrNull() ?: DEFAULT_CENTER, 14f)
            )
        }
    }

    private fun fitDriverFleet() {
        val map = googleMap ?: return
        val points = driverMarkers.values.map { it.position }

        runCatching {
            when {
                points.isEmpty() -> map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, 12.5f)
                )

                points.size == 1 || points.maxDistanceMeters() < 40.0 -> map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(points.first(), 15.5f)
                )

                else -> {
                    val bounds = LatLngBounds.Builder()
                    points.forEach(bounds::include)
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), dp(92)))
                }
            }
        }.onFailure {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(points.firstOrNull() ?: DEFAULT_CENTER, 14f)
            )
        }
    }

    private fun updateMapPadding() {
        val map = googleMap ?: return
        if (binding.root.height == 0) {
            binding.root.doOnLayout { updateMapPadding() }
            return
        }

        val topPadding = binding.topOverlay.height + dp(26)
        val bottomInset = (binding.root.height - binding.bottomSheetCard.top).coerceAtLeast(0)
        val stableInset = when (bottomSheetBehavior.state) {
            BottomSheetBehavior.STATE_COLLAPSED -> bottomSheetBehavior.peekHeight
            BottomSheetBehavior.STATE_HALF_EXPANDED,
            BottomSheetBehavior.STATE_EXPANDED -> bottomInset
            else -> max(bottomInset, bottomSheetBehavior.peekHeight)
        }
        val bottomPadding = minOf(stableInset, dp(420)) + dp(18)
        if (lastMapTopPadding == topPadding && lastMapBottomPadding == bottomPadding) return
        lastMapTopPadding = topPadding
        lastMapBottomPadding = bottomPadding
        map.setPadding(dp(18), topPadding, dp(18), bottomPadding)
    }

    private fun collapseSheetForMap() {
        if (::bottomSheetBehavior.isInitialized) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            isSheetCollapsed = true
        }
        updateMapPadding()
    }

    private fun isSheetFullyExpanded(): Boolean {
        return ::bottomSheetBehavior.isInitialized &&
            bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED
    }

    private fun scrollSheetToStep(step: BookingStep) {
        binding.sheetScrollView.post {
            val targetY = when (step) {
                BookingStep.ROUTE -> 0
                BookingStep.SELECT -> (binding.sectionSelection.top - dp(12)).coerceAtLeast(0)
                BookingStep.TRACK -> (binding.sectionTracking.top - dp(12)).coerceAtLeast(0)
            }
            binding.sheetScrollView.smoothScrollTo(0, targetY)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun stylePlacementButton(button: MaterialButton, isActive: Boolean) {
        button.alpha = if (isActive) 1f else 0.9f
        button.strokeWidth = dp(if (isActive) 2 else 1)
        button.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (isActive) R.color.pulse_orange else R.color.pulse_surface
            )
        )
        button.strokeColor = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (isActive) R.color.pulse_orange else R.color.pulse_outline
            )
        )
        button.setTextColor(
            ContextCompat.getColor(
                this,
                if (isActive) R.color.pulse_ink else R.color.pulse_text
            )
        )
    }

    private fun styleSearchTargetButton(button: MaterialButton, isActive: Boolean) {
        button.strokeWidth = dp(if (isActive) 0 else 1)
        button.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (isActive) R.color.pulse_orange else R.color.pulse_surface_alt
            )
        )
        button.strokeColor = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (isActive) R.color.pulse_orange else R.color.pulse_outline
            )
        )
        button.setTextColor(
            ContextCompat.getColor(
                this,
                if (isActive) R.color.pulse_ink else R.color.pulse_text
            )
        )
        button.alpha = if (button.isEnabled) 1f else 0.55f
    }

    private fun styleScreenButton(button: MaterialButton, isActive: Boolean) {
        button.strokeWidth = dp(if (isActive) 0 else 1)
        button.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (isActive) R.color.pulse_orange else R.color.pulse_surface
            )
        )
        button.strokeColor = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (isActive) R.color.pulse_orange else R.color.pulse_outline
            )
        )
        button.setTextColor(
            ContextCompat.getColor(
                this,
                if (isActive) R.color.pulse_ink else R.color.pulse_text
            )
        )
    }

    private fun List<LatLng>.maxDistanceMeters(): Double {
        var maxDistance = 0.0
        for (start in indices) {
            for (end in start + 1 until size) {
                maxDistance = maxOf(
                    maxDistance,
                    SphericalUtil.computeDistanceBetween(this[start], this[end])
                )
            }
        }
        return maxDistance
    }

    private fun buildPlaceholderView(message: String): TextView {
        return TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(context, R.color.pulse_muted))
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
    }

    private fun animateEntry(view: View, index: Int = 0) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(14).toFloat()
        view.scaleX = 0.985f
        view.scaleY = 0.985f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setStartDelay((index * 40L).coerceAtMost(180L))
            .start()
    }

    private fun activeRideStatusText(): String {
        return when (activeRide?.stage) {
            RideStage.DRIVER_TO_PICKUP -> "Driver is heading to pickup."
            RideStage.TRIP_TO_DESTINATION -> "Ride is on the way to destination."
            RideStage.COMPLETED_WAITING_RATING -> "Trip finished. Waiting for rating."
            null -> ""
        }
    }

    private fun routeSourceLabel(source: RouteSource): String {
        return when (source) {
            RouteSource.ORS -> "HeiGIT route"
            RouteSource.FALLBACK -> "Fallback route"
            RouteSource.SAME_POINT -> "Same point"
        }
    }

    private fun surgeMultiplier(): Double {
        val available = drivers.count { it.available }
        return when {
            available <= 2 -> 1.5
            available <= 4 -> 1.2
            else -> 1.0
        }
    }

    private fun driverMarkerHue(driver: Driver): Float {
        return when {
            activeRide?.driverId == driver.id -> BitmapDescriptorFactory.HUE_AZURE
            selectedDriverId == driver.id -> BitmapDescriptorFactory.HUE_GREEN
            driver.available -> BitmapDescriptorFactory.HUE_ORANGE
            else -> BitmapDescriptorFactory.HUE_ROSE
        }
    }

    private fun interpolatePolyline(points: List<LatLng>, progress: Float): LatLng {
        if (points.size == 1) return points.first()

        val segmentLengths = mutableListOf<Double>()
        var total = 0.0
        for (index in 0 until points.lastIndex) {
            val segment = SphericalUtil.computeDistanceBetween(points[index], points[index + 1])
            segmentLengths += segment
            total += segment
        }

        var target = total * progress
        for (index in segmentLengths.indices) {
            val segmentLength = segmentLengths[index]
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
        return "${max(1, (durationSeconds / 60.0).roundToInt())} min"
    }

    private fun Double.format(scale: Int): String {
        return "%.${scale}f".format(this)
    }

    private fun VehicleType.toRideTier(): RideTier {
        return when (this) {
            VehicleType.BIKE,
            VehicleType.MINI,
            VehicleType.POOL,
            VehicleType.ELECTRIC -> RideTier.ECONOMY

            VehicleType.SEDAN,
            VehicleType.LUXURY -> RideTier.PREMIUM

            VehicleType.SUV -> RideTier.XL
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val DEFAULT_CENTER = LatLng(12.9716, 77.5946)
        const val PICKUP_TAG = "pickup"
        const val DESTINATION_TAG = "destination"
        val DASH_PATTERN = listOf(Dash(28f), Gap(20f))
    }
}

private enum class BookingStep {
    ROUTE,
    SELECT,
    TRACK
}

private enum class AppScreen {
    RIDER,
    DRIVER
}

private enum class SearchTarget {
    PICKUP,
    DESTINATION
}
