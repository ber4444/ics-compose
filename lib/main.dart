import 'package:flutter/material.dart';
import 'twitter_client.dart';
import 'package:url_launcher/url_launcher.dart';
import 'dart:io' show Platform;
import 'package:http/http.dart' as http;
import 'package:flutter/services.dart';

void main() {
  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final newTextTheme = Theme.of(context).textTheme.apply(
          bodyColor: Colors.teal[900],
          displayColor: Colors.teal[900],
        );
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        primarySwatch: Colors.red,
        primaryTextTheme: newTextTheme,
        textTheme: newTextTheme,
        iconTheme: IconThemeData(
          color: Colors.red[400],
        ),
      ),
      home: LoginPage(),
    );
  }
}

class LoginPage extends StatefulWidget {
  @override
  _LoginPageState createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final TwitterClient _client = new TwitterClient();

  @override
  Widget build(BuildContext context) {
    return new MaterialApp(
      home: new Scaffold(
        body: new Stack(
          children: <Widget>[
            new Container(
              decoration: new BoxDecoration(
                image: new DecorationImage(
                  image: new AssetImage("assets/image3.jpeg"),
                  fit: BoxFit.cover,
                ),
              ),
            ),
            new Center(child: new Builder(builder: (BuildContext context) {
              return SizedBox(width: 200, child: Column(
                children: <Widget>[
                  new Padding(
                    padding: const EdgeInsets.all(20.0),
                  ),
                  new SwitchListTile(
                      title: const Text('Audio only'),
                      value: _audioOnly,
                      onChanged: (value) {
                        setState(() {
                          _audioOnly = value;
                        });
                      }
                  ),
                  new MaterialButton(
                    //padding: new EdgeInsets.all(16.0),
                    minWidth: 150.0,
                    onPressed: () =>
                        _client
                            .getMembership("LivingPresence")
                            .then((membership) {
                          if (membership) {
                            showDialog(
                                context: context,
                                builder: (BuildContext context) {
                                  return AlertDialog(
                                    title: Text('Videos List'),
                                    content: new FutureBuilder<List<int>>(
                                      future: _getVidList(),
                                      builder: (BuildContext context,
                                          AsyncSnapshot<List<int>> snapshot) {
                                        if (snapshot.connectionState ==
                                            ConnectionState.waiting) {
                                          return Center(
                                              child: CircularProgressIndicator(
                                                  value: null));
                                        } else {
                                          if (snapshot.hasError)
                                            return Center(
                                                child: Text(
                                                    'Error: ${snapshot
                                                        .error}'));
                                          else
                                            return Center(
                                                child: _setupAlertDialoadContainer(
                                                    snapshot.data));
                                        }
                                      },
                                    ),
                                  );
                                });
                          } else {
                            ScaffoldMessenger.of(context).showSnackBar(new SnackBar(
                              content: new Text(
                                  'Try again in 15 minutes.'), // rate limited to 15 requests
                            ));
                          }
                        }).catchError((e) => print(e)),
                    child: new Text('Live Events'),
                    color: Colors.red[400],
                  ),
                  new Padding(
                    padding: const EdgeInsets.all(5.0),
                  ),
                  Text(
                    'Videos play at 420p.\nFOF doesn\'t do HD quality.',
                    textAlign: TextAlign.center,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        backgroundColor: Color.fromRGBO(255, 255, 255, 0.4)),
                  ),
                  new Padding(
                    padding: const EdgeInsets.all(5.0),
                  ),
                  new MaterialButton(
                    minWidth: 150.0,
                    onPressed: () =>
                        _handleURLButtonPress(
                            Uri.parse("https://www.propylaia.org:443/wordpress/")),
                    child: new Text('Teaching Payments'),
                    color: Colors.red[400],
                  ),
                  new Padding(
                    padding: const EdgeInsets.all(5.0),
                  ),
                  new MaterialButton(
                    minWidth: 150.0,
                    onPressed: () =>
                        _handleURLButtonPress(Uri.https("s4898.americommerce.com")),
                    child: new Text('Event Payments'),
                    color: Colors.red[400],
                  ),
                ],
              )
              );
            })),
          ],
        ),
      ),
    );
  }

  Future<List<int>> _getVidList() async {
    final List<int> good = [];
    final client = http.Client();
    var weekday = DateTime.now().weekday;
    if (weekday > 3)
      try {
        for (int i = 1; i <= weekday * 2; i++) {
          await client.get(_getUrl(i)).then(
              (response) => {if (response.statusCode != 404) good.add(i)});
        }
      } finally {
        client.close();
      }
    else // show last week's meetings on Mon-Wed as there are none on those days
      try {
        for (int i = 15; i > 0; i--) {
          await client.get(_getUrl(i)).then(
                  (response) => {if (response.statusCode != 404) good.add(i)});
        }
      } finally {
        client.close();
      }
    return good;
  }

  var events = [
    "Monday morning",
    "Monday evening",
    "Tuesday morning",
    "Tuesday evening",
    "Wednesday morning",
    "Wednesday evening",
    "Thursday morning",
    "Thursday evening",
    "Friday morning",
    "Friday evening",
    "Saturday morning",
    "Saturday evening",
    "Sunday morning",
    "Sunday evening"
  ];
  Widget _setupAlertDialoadContainer(List<int> list) {
    return Container(
      width: 300,
      child: ListView.builder(
        shrinkWrap: true,
        itemCount: list.length,
        itemBuilder: (BuildContext context, int index) {
          return ListTile(
            title: Text(events[list[index] - 1]),
            onTap: () {
              var url = _getUrl(list[index]);
              _goToFullVideos(url);
            },
          );
        },
      ),
    );
  }

  Uri _getUrl(int i) {
    return Uri.parse("https://5e874c546d6cf.streamlock.net:443/live/event$i" +
        (_audioOnly?"_aac":"") + "/playlist.m3u8?DVR");
  }

  bool _audioOnly = false;

  void _goToFullVideos(Uri url) async {
    if (Platform.isAndroid) {
      const MethodChannel _channel = MethodChannel("samples.flutter.dev/gabor");
      await _channel.invokeMethod<void>('openFullVideo', {"url": url.toString(), "audioOnly": _audioOnly});
    }
  }

  void _handleURLButtonPress(Uri url) async {
    if (await canLaunchUrl(url)) {
      await launchUrl(url);
    } else {
      throw 'Could not launch $url';
    }
  }
}
