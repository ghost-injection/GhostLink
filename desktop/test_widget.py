"""UI checks with an isolated controller and window-state file."""
import tempfile
import time
import unittest
from pathlib import Path
import widget

class Controller:
    process = None
    def disconnect(self):
        pass

class WidgetTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.app = widget.App({}, lambda config, callback: Controller(), lambda *args: None, Path('.'), background=True)
        self.app.window_file = Path(self.temp.name) / 'window.json'
    def tearDown(self):
        self.app.quit()
        self.temp.cleanup()
    def test_connection_expressions(self):
        for status, mood in [('LINK ESTABLISHED', 'happy'), ('ACTION NEEDED', 'angry'), ('SESSION CLOSED', 'sad'), ('CONNECTING', 'focusing')]:
            self.app.update_status(status, 'test')
            self.assertEqual(self.app.expression, mood)
            self.app.draw_face(time.monotonic())
            self.assertTrue(self.app.c.find_withtag('expression'))
    def test_layout_switch_and_hit_targets(self):
        import json
        from types import SimpleNamespace
        app=self.app
        app.root.deiconify()
        for layout in ('horizontal','vertical','horizontal'):
            app.set_layout(layout)
            app.root.update_idletasks()
            width,height=app.dimensions()
            self.assertEqual(app.root.winfo_width(),round(width*app.density))
            self.assertEqual(app.root.winfo_height(),round(height*app.density))
            for x0,y0,x1,y1,fn,item in app.regions:
                point=app.c.project([(x0+x1)/2,(y0+y1)/2])
                event=SimpleNamespace(x=point[0]*app.density,y=point[1]*app.density)
                x,y=app.pointer_coordinates(event)
                self.assertTrue(x0<=x<=x1 and y0<=y<=y1)
                self.assertTrue(0<=point[0]<=width and 0<=point[1]<=height)
            app.draw_face(time.monotonic())
            self.assertEqual(json.loads(app.window_file.read_text())['layout'],layout)
        app.set_layout('vertical')
    def test_resize_preserves_coordinates(self):
        before = self.app.c.coords(self.app.ring)
        for factor in (1.2, .9, 1.4, 1):
            self.app.resize_canvas(self.app.base_density * factor)
            for a, b in zip(before, self.app.c.coords(self.app.ring)):
                self.assertAlmostEqual(a, b, places=5)
        self.app.save_window()
        self.assertTrue(self.app.window_file.exists())

if __name__ == '__main__':
    unittest.main()

