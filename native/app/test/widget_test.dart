import 'package:flutter_test/flutter_test.dart';

import 'package:farmcamera/main.dart';

void main() {
  testWidgets('App boots and shows the title', (WidgetTester tester) async {
    await tester.pumpWidget(const FarmCameraApp());
    await tester.pump();

    expect(find.text('定点撮影POC'), findsWidgets);
  });
}
