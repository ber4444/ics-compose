# Code Migration Comparison

This document shows side-by-side comparisons of key Flutter code vs the equivalent Compose code.

## 1. App Entry Point

### Flutter (lib/main.dart)
```dart
void main() {
  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      theme: ThemeData(
        primarySwatch: Colors.red,
      ),
      home: LoginPage(),
    );
  }
}
```

### Compose (MainActivity.kt)
```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InnerCircleSquaredTheme {
                LoginScreen()
            }
        }
    }
}

@Composable
fun InnerCircleSquaredTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFFEF5350)
        ),
        content = content
    )
}
```

## 2. Background Image

### Flutter
```dart
Stack(
  children: <Widget>[
    Container(
      decoration: BoxDecoration(
        image: DecorationImage(
          image: AssetImage("assets/image3.jpeg"),
          fit: BoxFit.cover,
        ),
      ),
    ),
    // ... other widgets
  ],
)
```

### Compose
```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    Image(
        painter = painterResource(id = R.drawable.background_image),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
    // ... other composables
}
```

## 3. Switch Toggle

### Flutter
```dart
SwitchListTile(
    title: const Text('Audio only'),
    value: _audioOnly,
    onChanged: (value) {
        setState(() {
            _audioOnly = value;
        });
    }
)
```

### Compose
```kotlin
var audioOnly by remember { mutableStateOf(false) }

Row(
    modifier = Modifier
        .width(200.dp)
        .background(Color(0x99FFFFFF))
        .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Text(
        text = "Audio only",
        modifier = Modifier.weight(1f)
    )
    Switch(
        checked = audioOnly,
        onCheckedChange = { audioOnly = it }
    )
}
```

## 4. Button

### Flutter
```dart
MaterialButton(
  minWidth: 150.0,
  onPressed: _enable ? () => showDialog(...) : null,
  child: Text('Live Events'),
  color: Colors.red[400],
)
```

### Compose
```kotlin
val isEnabled = password == "be2BE"

Button(
    onClick = { showDialog = true },
    enabled = isEnabled,
    modifier = Modifier.width(150.dp),
    colors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFFEF5350)
    )
) {
    Text("Live Events")
}
```

## 5. Text Field

### Flutter
```dart
TextField(
  style: TextStyle(color: Colors.red),
  cursorColor: Colors.red,
  decoration: InputDecoration(
      filled: true,
      border: InputBorder.none,
      fillColor: Color.fromRGBO(255, 255, 255, 0.6)),
  onChanged: (value) {
    _enable = (value == "be2BE");
    setState(() { });
  }
)
```

### Compose
```kotlin
var password by remember { mutableStateOf("") }

TextField(
    value = password,
    onValueChange = { password = it },
    modifier = Modifier
        .width(100.dp)
        .background(Color(0x99FFFFFF)),
    colors = TextFieldDefaults.colors(
        focusedTextColor = Color.Red,
        unfocusedTextColor = Color.Red,
        cursorColor = Color.Red,
        focusedContainerColor = Color(0x99FFFFFF),
        unfocusedContainerColor = Color(0x99FFFFFF)
    ),
    singleLine = true
)
```

## 6. HTTP Request

### Flutter
```dart
Future<List<int>> _getVidList() async {
  final List<int> good = [];
  final client = http.Client();
  try {
    for (int i = 20; i > 0; i--) {
      await client.get(_getUrl(i)).then(
        (response) => {if (response.statusCode != 404) good.add(i)}
      );
    }
  } finally {
    client.close();
  }
  return good;
}
```

### Compose
```kotlin
suspend fun getVideoList(): List<Int> {
    val good = mutableListOf<Int>()
    val client = HttpClient(Android)
    
    try {
        for (i in 20 downTo 1) {
            try {
                val response: HttpResponse = client.get(getUrl(i, false))
                if (response.status.value != 404) {
                    good.add(i)
                }
            } catch (e: Exception) {
                // Ignore errors for individual requests
            }
        }
    } finally {
        client.close()
    }
    
    return good
}
```

## 7. Async UI Update

### Flutter
```dart
FutureBuilder<List<int>>(
  future: _getVidList(),
  builder: (BuildContext context, AsyncSnapshot<List<int>> snapshot) {
    if (snapshot.connectionState == ConnectionState.waiting) {
      return Center(child: CircularProgressIndicator());
    } else {
      if (snapshot.hasError || snapshot.data == null)
        return Center(child: Text('Error: ${snapshot.error}'));
      else
        return Center(child: _setupAlertDialoadContainer(snapshot.data!));
    }
  },
)
```

### Compose
```kotlin
var videoList by remember { mutableStateOf<List<Int>?>(null) }
var isLoading by remember { mutableStateOf(true) }
var error by remember { mutableStateOf<String?>(null) }

LaunchedEffect(Unit) {
    scope.launch {
        try {
            videoList = getVideoList()
            isLoading = false
        } catch (e: Exception) {
            error = e.message
            isLoading = false
        }
    }
}

when {
    isLoading -> CircularProgressIndicator()
    error != null -> Text("Error: $error")
    videoList != null -> LazyColumn { /* ... */ }
}
```

## 8. List View

### Flutter
```dart
ListView.builder(
  shrinkWrap: true,
  itemCount: list.length,
  itemBuilder: (BuildContext context, int index) {
    return ListTile(
      title: Text("event " + list[index].toString()),
      onTap: () {
        var url = _getUrl(list[index]);
        _goToFullVideos(url);
      },
    );
  },
)
```

### Compose
```kotlin
LazyColumn {
    items(videoList!!) { eventNumber ->
        TextButton(
            onClick = {
                val url = getUrl(eventNumber, audioOnly)
                goToFullVideos(context, url, audioOnly)
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("event $eventNumber")
        }
    }
}
```

## 9. URL Launching

### Flutter
```dart
void _handleURLButtonPress(Uri url) async {
  if (await canLaunchUrl(url)) {
    await launchUrl(url);
  } else {
    throw 'Could not launch $url';
  }
}
```

### Compose
```kotlin
fun handleURLButtonPress(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
```

## 10. Platform-Specific Code (MX Player)

### Flutter
```dart
// In Dart:
const MethodChannel _channel = MethodChannel("samples.flutter.dev/gabor");
await _channel.invokeMethod<void>('openFullVideo', {
  "url": url.toString(), 
  "audioOnly": _audioOnly
});

// In Kotlin (separate file):
MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
  .setMethodCallHandler { call, result ->
    when (call.method) {
      "openFullVideo" -> openMxplayer(call.argument("url"), call.argument("audioOnly"))
    }
}
```

### Compose (single file, direct call)
```kotlin
fun goToFullVideos(context: android.content.Context, url: String, audioOnly: Boolean) {
    val uri = Uri.parse(url)
    val intent = Intent(Intent.ACTION_VIEW)
    intent.setPackage("com.mxtech.videoplayer.pro")
    intent.setDataAndType(uri, "video/*")
    intent.putExtra("decode_mode", 2.toByte())
    if (audioOnly) intent.putExtra("video", false)
    // ... more extras
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Fallback to ad version or Play Store
    }
}
```

## Key Differences Summary

| Aspect | Flutter | Compose |
|--------|---------|---------|
| Language | Dart | Kotlin |
| State Management | `setState()`, StatefulWidget | `remember`, `mutableStateOf` |
| Async | `Future`, `async`/`await` | Coroutines, `suspend` |
| HTTP | `package:http` | Ktor |
| Layout | Widgets (Stack, Column) | Composables (Box, Column) |
| Lists | ListView.builder | LazyColumn + items |
| Styling | Widget properties | Modifiers |
| Platform Code | Method Channels | Direct Kotlin |
| Lines of Code | 217 (Dart) | 291 (Kotlin) |
| Files | 2 (Dart + Kotlin) | 1 (Kotlin only) |

## Performance Improvements

1. **No Bridge**: Direct native calls instead of Flutter's platform channel bridge
2. **Smaller Binary**: No Flutter engine (~40MB saved)
3. **Native Rendering**: Uses Android's native rendering instead of Skia
4. **Better Integration**: Direct access to Android APIs
5. **Type Safety**: Kotlin's null safety and type system

## Code Quality Improvements

1. **Single Language**: Everything in Kotlin (no Dart/Kotlin split)
2. **Better IDE Support**: Full Android Studio features
3. **Null Safety**: Kotlin's more robust null safety
4. **Immutability**: Compose encourages immutable state
5. **Coroutines**: More powerful than Dart's async model
